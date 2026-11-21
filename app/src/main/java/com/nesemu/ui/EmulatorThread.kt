package com.nesemu.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.nesemu.core.Console
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs the console at 60 fps. With sound on, blocking AudioTrack writes pace the
 * emulation, which keeps audio and video in sync without drift.
 */
class EmulatorThread(
    private val console: Console,
    private val view: GameView,
) : Thread("emulator") {
    @Volatile var running = true
    @Volatile var paused = false
    @Volatile var soundOn = true
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    private val lock = Object()

    /** Runs [task] on the emulation thread between two frames. */
    fun post(task: Runnable) {
        tasks.add(task)
        synchronized(lock) { lock.notifyAll() }
    }

    fun setPausedState(value: Boolean) {
        paused = value
        synchronized(lock) { lock.notifyAll() }
    }

    fun shutdown() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        join(1000)
    }

    override fun run() {
        val track = createAudioTrack()
        val samples = ShortArray(4096)
        val frameNs = (1_000_000_000.0 / 60.0988).toLong()
        var next = System.nanoTime()
        var playing = false
        try {
            while (running) {
                while (true) tasks.poll()?.run() ?: break
                if (paused) {
                    if (playing) { track.pause(); track.flush(); playing = false }
                    synchronized(lock) { if (paused && running && tasks.isEmpty()) lock.wait(100) }
                    next = System.nanoTime()
                    continue
                }
                console.stepFrame()
                view.drawFrame(console.ppu.frame)
                val n = console.apu.drainSamples(samples)
                if (soundOn) {
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
