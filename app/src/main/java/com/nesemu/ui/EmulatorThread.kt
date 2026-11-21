package com.nesemu.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nesemu.core.Buttons
import com.nesemu.core.Console
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs the console at 60 fps. With sound on, blocking AudioTrack writes pace the
 * emulation, which keeps audio and video in sync without drift. Also handles
 * turbo buttons, fast-forward and rewind.
 */
class EmulatorThread(
    private val console: Console,
    private val view: GameView,
) : Thread("emulator") {
    @Volatile var running = true
    @Volatile var paused = false
    @Volatile var soundOn = true

    /** Regular buttons held on the pad (see [Buttons]). */
    @Volatile var held = 0
    /** Buttons that auto-fire while held. */
    @Volatile var turbo = 0
    @Volatile var fastForward = false
    @Volatile var fastSpeed = 3
    @Volatile var rewinding = false

    /** Frames actually emulated while not paused, for play-time statistics. */
    @Volatile var framesPlayed = 0L
        private set

    private val rewind = RewindBuffer(capacity = 360) // ~12 s at one snapshot per 2 frames
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val lock = Object()
    private var frameIndex = 0L

    /** Runs [task] on the emulation thread between two frames. */
    fun post(task: Runnable) {
        tasks.add(task)
        synchronized(lock) { lock.notifyAll() }
    }

    fun setPausedState(value: Boolean) {
        paused = value
        synchronized(lock) { lock.notifyAll() }
    }

    /** Forget rewind history, e.g. after loading a save. Must run on this thread. */
    fun clearRewind() = rewind.clear()

    fun shutdown() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        join(2000)
    }

    private fun runTasks() {
        while (true) tasks.poll()?.run() ?: break
    }

    private fun applyInput() {
        // Turbo fires at 15 Hz: 2 frames pressed, 2 frames released.
        val turboOn = (frameIndex shr 1) and 1L == 0L
        console.buttons1 = held or (if (turboOn) turbo else 0)
    }

    override fun run() {
        val track = createAudioTrack()
        val samples = ShortArray(4096)
        val frameNs = (1_000_000_000.0 / 60.0988).toLong()
        var next = System.nanoTime()
        var playing = false
        try {
            while (running) {
                runTasks()
                if (paused) {
                    if (playing) { track.pause(); track.flush(); playing = false }
                    synchronized(lock) { if (paused && running && tasks.isEmpty()) lock.wait(100) }
                    next = System.nanoTime()
                    continue
                }

                var useAudio = soundOn
                if (rewinding) {
                    useAudio = false
                    val state = rewind.pop()
                    if (state != null) {
                        console.loadState(state)
                        applyInput()
                        console.stepFrame()
                    }
                    console.apu.drainSamples(samples)
                } else {
                    val steps = if (fastForward) fastSpeed else 1
                    if (fastForward) useAudio = false
                    for (i in 0 until steps) {
                        if (frameIndex and 1L == 0L) rewind.push(console.saveState())
                        applyInput()
                        console.stepFrame()
                        frameIndex++
                        framesPlayed++
                        if (i < steps - 1) console.apu.drainSamples(samples)
                    }
                }
                view.drawFrame(console.ppu.frame)

                val n = console.apu.drainSamples(samples)
                if (useAudio) {
                    if (!playing) { track.play(); playing = true }
                    track.write(samples, 0, n) // blocks when the buffer is full
                    next = System.nanoTime()
                } else {
                    if (playing) { track.pause(); track.flush(); playing = false }
                    next += frameNs
                    val wait = next - System.nanoTime()
                    if (wait > 0) sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    else if (wait < -frameNs * 5) next = System.nanoTime() // fell behind: resync
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            runTasks() // let pending saves finish
            track.release()
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val rate = SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // ~70 ms buffer: low latency but tolerant of the occasional slow frame.
        val size = maxOf(min, rate / 14 * 2)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    companion object {
        const val SAMPLE_RATE = 44100
    }
}

/** Fixed-size ring of save states, newest last. */
class RewindBuffer(private val capacity: Int) {
    private val states = arrayOfNulls<ByteArray>(capacity)
    private var start = 0
    private var size = 0

    fun push(state: ByteArray) {
        val idx = (start + size) % capacity
        states[idx] = state
        if (size < capacity) size++ else start = (start + 1) % capacity
    }

    fun pop(): ByteArray? {
        if (size == 0) return null
        size--
        val idx = (start + size) % capacity
        return states[idx].also { states[idx] = null }
    }

    fun clear() {
        states.fill(null)
        start = 0; size = 0
    }
}
