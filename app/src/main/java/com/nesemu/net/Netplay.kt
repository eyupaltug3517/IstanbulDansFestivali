package com.nesemu.net

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Delay-based lockstep netplay for two players. Only controller inputs travel over
 * the network: both phones run the same emulation, so every frame advances only once
 * the inputs of both players for that frame are known. Local input is scheduled
 * [delay] frames ahead, which hides the network latency.
 *
 * The host is player 1 and owns the game; the guest (player 2) receives the starting
 * save state. Every message carries an epoch so a restart (e.g. after a desync)
 * cleanly discards old inputs.
 */
class Netplay(private val transport: Transport, val isHost: Boolean, val room: String) : Transport.Listener {
    interface Events {
        fun onConnected() {}
        /** Host only: a guest is in the room; call [hostStart] from the emulation thread. */
        fun onPeerJoined() {}
        /** Guest only: the host started (or restarted) the game. */
        fun onStart(crc: Long, name: String) {}
        fun onPeerLeft() {}
        fun onDesync() {}
        fun onError(message: String) {}
    }

    @Volatile var events: Events? = null
    @Volatile var delay = 4
        private set
    @Volatile var pingMs = -1
        private set
    @Volatile var started = false
        private set
    @Volatile var epoch = 0
        private set
    @Volatile var stalls = 0L
        private set

    private val lock = Object()
    private val local = IntArray(RING)
    private val remote = IntArray(RING)
    private var localNext = 0
    private var remoteNext = 0
    private val outgoing = StringBuilder()
    private var outgoingStart = 0
    private val localHashes = HashMap<Int, Int>()
    private val remoteHashes = HashMap<Int, Int>()
    private var pendingState: ByteArray? = null
    private var closed = false
    private var timer: ScheduledExecutorService? = null
    private var lastRemoteSeen = System.currentTimeMillis()

    fun connect() {
        transport.connect(room, this)
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        try { transport.send("bye", JSONObject()) } catch (_: Exception) {}
        timer?.shutdownNow()
        transport.close()
    }

    // ---- Transport.Listener ----

    override fun onJoined() {
        events?.onConnected()
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "netplay-timer").apply { isDaemon = true } }.also {
            it.scheduleAtFixedRate({ tick() }, 0, 1, TimeUnit.SECONDS)
        }
    }

    private fun tick() {
        if (!isHost && !started) transport.send("hello", JSONObject())
        if (started) transport.send("ping", JSONObject().put("t", now()))
        if (started && System.currentTimeMillis() - lastRemoteSeen > PEER_TIMEOUT_MS) {
            lastRemoteSeen = System.currentTimeMillis()
            events?.onPeerLeft()
        }
    }

    override fun onError(message: String) {
        events?.onError(message)
    }

    override fun onMessage(event: String, payload: JSONObject) {
        lastRemoteSeen = System.currentTimeMillis()
        when (event) {
            "hello" -> if (isHost) {
                // Measure latency before choosing the input delay.
                transport.send("ping", JSONObject().put("t", now()).put("join", true))
            }
            "ping" -> transport.send("pong", payload)
            "pong" -> {
                val rtt = (now() - payload.optLong("t")).toInt()
                pingMs = if (pingMs < 0) rtt else (pingMs * 3 + rtt) / 4
                if (payload.optBoolean("join") && isHost) {
                    delay = delayFor(rtt)
                    events?.onPeerJoined()
                }
            }
            "start" -> if (!isHost) receiveStart(payload)
            "in" -> receiveInputs(payload)
            "h" -> receiveHash(payload)
            "bye" -> events?.onPeerLeft()
        }
    }

    // ---- Start / restart ----

    /** Host, on the emulation thread: (re)start the session from [state]. Frame counting restarts at 0. */
    fun hostStart(state: ByteArray, crc: Long, name: String) {
        synchronized(lock) {
            epoch++
            resetBuffers()
            started = true
        }
        transport.send("start", JSONObject()
            .put("e", epoch).put("crc", crc).put("name", name).put("d", delay)
            .put("s", Base64.getEncoder().encodeToString(deflate(state))))
    }

    private fun receiveStart(p: JSONObject) {
        val state = inflate(Base64.getDecoder().decode(p.getString("s")))
        synchronized(lock) {
            epoch = p.getInt("e")
            delay = p.getInt("d")
            resetBuffers()
            pendingState = state
            started = true
        }
        events?.onStart(p.getLong("crc"), p.optString("name"))
    }

    /** Guest, on the emulation thread: the state to load before frame 0 of a new epoch. */
    fun takePendingState(): ByteArray? = synchronized(lock) { pendingState.also { pendingState = null } }

    private fun resetBuffers() {
        local.fill(0); remote.fill(0)
        localNext = delay; remoteNext = delay
        outgoing.setLength(0)
        outgoingStart = delay
        localHashes.clear(); remoteHashes.clear()
    }

    // ---- Lockstep (emulation thread) ----

    /** Schedules this player's buttons for [frame]; frames must be pushed in order. */
    fun pushLocal(frame: Int, buttons: Int) {
        var send: JSONObject? = null
        synchronized(lock) {
            if (!started || frame != localNext) return
            local[frame and MASK] = buttons
            localNext++
            if (outgoing.isEmpty()) outgoingStart = frame
            outgoing.append(HEX[buttons shr 4]).append(HEX[buttons and 15])
            if (outgoing.length >= BATCH * 2) {
                send = JSONObject().put("e", epoch).put("f", outgoingStart).put("d", outgoing.toString())
                outgoing.setLength(0)
            }
        }
        send?.let { transport.send("in", it) }
    }

    /** True once this frame's local input was scheduled (so the caller pushes each frame once). */
    fun hasLocal(frame: Int): Boolean = synchronized(lock) { frame < localNext }

    /** Inputs for [frame] as (player1 | player2 shl 8), or -1 if still waiting for the other player. */
    fun inputs(frame: Int): Int = synchronized(lock) {
        if (!started || pendingState != null || frame >= localNext || frame >= remoteNext) {
            stalls++
            return -1
        }
        val l = local[frame and MASK]; val r = remote[frame and MASK]
        if (isHost) l or (r shl 8) else r or (l shl 8)
    }

    private fun receiveInputs(p: JSONObject) {
        synchronized(lock) {
            if (p.optInt("e") != epoch) return
            val f = p.getInt("f")
            val d = p.getString("d")
            for (i in 0 until d.length / 2) {
                val frame = f + i
                if (frame == remoteNext) {
                    remote[frame and MASK] = d.substring(i * 2, i * 2 + 2).toInt(16)
                    remoteNext++
                }
            }
        }
    }

    // ---- Desync detection ----

    fun reportHash(frame: Int, hash: Int) {
        val e: Int
        synchronized(lock) {
            e = epoch
            localHashes[frame] = hash
            checkHash(frame)
        }
        transport.send("h", JSONObject().put("e", e).put("f", frame).put("h", hash))
    }

    private fun receiveHash(p: JSONObject) {
        synchronized(lock) {
            if (p.optInt("e") != epoch) return
            val f = p.getInt("f")
            remoteHashes[f] = p.getInt("h")
            checkHash(f)
        }
    }

    private fun checkHash(frame: Int) {
        val a = localHashes[frame] ?: return
        val b = remoteHashes[frame] ?: return
        localHashes.remove(frame); remoteHashes.remove(frame)
        if (a != b) events?.onDesync()
    }

    companion object {
        private const val RING = 1024
        private const val MASK = RING - 1
        /** Frames per input packet: keeps traffic near 30 messages/s per player. */
        private const val BATCH = 2
        const val HASH_INTERVAL = 120
        private const val PEER_TIMEOUT_MS = 8000L
        private val HEX = "0123456789abcdef".toCharArray()

        private fun now() = System.nanoTime() / 1_000_000

        /** Enough frames to cover one-way latency plus batching, within a playable range. */
        fun delayFor(rttMs: Int): Int = (Math.ceil((rttMs / 2.0 + 25.0) / 16.67).toInt() + 1).coerceIn(3, 12)

        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_SPEED)
            d.setInput(data); d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        fun inflate(data: ByteArray): ByteArray {
            val i = Inflater()
            i.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && (i.needsInput() || i.needsDictionary())) break
                out.write(buf, 0, n)
            }
            i.end()
            return out.toByteArray()
        }
    }
}
