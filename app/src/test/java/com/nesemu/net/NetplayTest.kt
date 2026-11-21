package com.nesemu.net

import com.nesemu.core.Console
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** In-memory room that delivers messages to the other member after [latencyMs]. */
class LoopbackRoom(private val latencyMs: Long) {
    private val members = ArrayList<Transport.Listener>()
    private val exec = Executors.newSingleThreadScheduledExecutor()

    fun transport() = object : Transport {
        lateinit var me: Transport.Listener
        override fun connect(room: String, listener: Transport.Listener) {
            me = listener
            synchronized(members) { members.add(listener) }
            exec.execute { listener.onJoined() }
        }
        override fun send(event: String, payload: JSONObject) {
            val copy = JSONObject(payload.toString())
            val targets = synchronized(members) { members.filter { it !== me } }
            exec.schedule({ targets.forEach { it.onMessage(event, copy) } }, latencyMs, TimeUnit.MILLISECONDS)
        }
        override fun close() {}
    }
}

class NetplayTest {
    private fun rom(): ByteArray? = File("src/test/resources/roms/nestest.nes").takeIf { it.exists() }?.readBytes()

    @Test
    fun bothPlayersStayInSync() {
        val rom = rom()
        assumeTrue(rom != null)
        val room = LoopbackRoom(latencyMs = 40)
        val hostNet = Netplay(room.transport(), isHost = true, room = "1234")
        val guestNet = Netplay(room.transport(), isHost = false, room = "1234")
        val host = Console(rom!!)
        val guest = Console(rom)
        // Diverge the guest on purpose: the start state must overwrite it.
        repeat(17) { guest.stepFrame() }
        val hostDriver = NetplayDriver(host, hostNet)
        val guestDriver = NetplayDriver(guest, guestNet)
        val peerJoined = AtomicBoolean(false)
        var desync = false
        hostNet.events = object : Netplay.Events {
            override fun onPeerJoined() { peerJoined.set(true) }
            override fun onDesync() { desync = true }
        }
        guestNet.events = object : Netplay.Events {
            override fun onDesync() { desync = true }
        }
        hostNet.connect(); guestNet.connect()

        val frames = 600
        val hostHashes = IntArray(frames + 1)
        val guestHashes = IntArray(frames + 1)
        val t1 = Thread {
            val deadline = System.currentTimeMillis() + 60_000
            while (!peerJoined.get() && System.currentTimeMillis() < deadline) Thread.sleep(5)
            hostDriver.hostRestart("test")
            while (hostDriver.frame < frames && System.currentTimeMillis() < deadline) {
                val buttons = (hostDriver.frame * 7919) and 0xFF
                if (hostDriver.step(buttons)) hostHashes[hostDriver.frame] = host.stateHash() else Thread.sleep(1)
            }
        }
        val t2 = Thread {
            val deadline = System.currentTimeMillis() + 60_000
            while (guestDriver.frame < frames && System.currentTimeMillis() < deadline) {
                val buttons = (guestDriver.frame * 104729) and 0xFF
                if (guestDriver.step(buttons)) guestHashes[guestDriver.frame] = guest.stateHash() else Thread.sleep(1)
            }
        }
        t1.start(); t2.start(); t1.join(); t2.join()
        assertEquals(frames, hostDriver.frame)
        assertEquals(frames, guestDriver.frame)
        for (f in 1..frames) assertEquals("hash at frame $f", hostHashes[f], guestHashes[f])
        assertTrue(host.ppu.frame.contentEquals(guest.ppu.frame))
        assertFalse(desync)
        println("delay=${hostNet.delay} hostStalls=${hostNet.stalls} guestStalls=${guestNet.stalls}")
    }

    @Test
    fun delayGrowsWithLatency() {
        assertEquals(3, Netplay.delayFor(10))
        assertTrue(Netplay.delayFor(200) > Netplay.delayFor(60))
        assertEquals(12, Netplay.delayFor(2000))
    }

    @Test
    fun deflateRoundTrip() {
        val data = ByteArray(24000) { (it % 7).toByte() }
        assertTrue(Netplay.inflate(Netplay.deflate(data)).contentEquals(data))
    }
}
