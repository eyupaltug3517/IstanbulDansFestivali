package com.nesemu.net

import com.nesemu.core.Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Full lockstep session over the real Supabase relay at real-time speed. Opt-in: NES_LIVE_NET=1. */
class NetplayLiveTest {
    @Test
    fun realtimeSessionStaysInSync() {
        assumeTrue(System.getenv("NES_LIVE_NET") != null)
        val rom = File("src/test/resources/roms/nestest.nes").readBytes()
        val code = (1000..9999).random().toString()
        val hostNet = Netplay(SupabaseTransport(NetConfig.PROJECT_URL, NetConfig.API_KEY), true, code)
        val guestNet = Netplay(SupabaseTransport(NetConfig.PROJECT_URL, NetConfig.API_KEY), false, code)
        val host = Console(rom); val guest = Console(rom)
        val hd = NetplayDriver(host, hostNet); val gd = NetplayDriver(guest, guestNet)
        val joined = AtomicBoolean(false)
        var desync = false
        hostNet.events = object : Netplay.Events {
            override fun onPeerJoined() { joined.set(true) }
            override fun onDesync() { desync = true }
            override fun onError(message: String) { println("host error $message") }
        }
        guestNet.events = object : Netplay.Events {
            override fun onDesync() { desync = true }
            override fun onError(message: String) { println("guest error $message") }
        }
        hostNet.connect(); Thread.sleep(300); guestNet.connect()
        val frames = 600 // 10 seconds
        val hh = IntArray(frames + 1); val gh = IntArray(frames + 1)
        fun loop(d: NetplayDriver, c: Console, hashes: IntArray, seed: Int, host: Boolean) {
            val deadline = System.currentTimeMillis() + 60_000
            if (host) {
                while (!joined.get() && System.currentTimeMillis() < deadline) Thread.sleep(5)
                d.hostRestart("live")
            }
            var next = System.nanoTime()
            while (d.frame < frames && System.currentTimeMillis() < deadline) {
                if (d.step((d.frame * seed) and 0xFF)) {
                    hashes[d.frame] = c.stateHash()
                    next += 16_666_667
                    val w = next - System.nanoTime()
                    if (w > 0) Thread.sleep(w / 1_000_000, (w % 1_000_000).toInt())
                } else { Thread.sleep(1); next = System.nanoTime() }
            }
        }
        val start = System.currentTimeMillis()
        val t1 = Thread { loop(hd, host, hh, 7919, true) }
        val t2 = Thread { loop(gd, guest, gh, 104729, false) }
        t1.start(); t2.start(); t1.join(); t2.join()
        val secs = (System.currentTimeMillis() - start) / 1000.0
        println("LIVE delay=${hostNet.delay} ping=${hostNet.pingMs}ms frames=${hd.frame}/${gd.frame} time=${secs}s")
        hostNet.close(); guestNet.close()
        assertEquals(frames, hd.frame); assertEquals(frames, gd.frame)
        for (f in 1..frames) assertEquals("frame $f", hh[f], gh[f])
        assertFalse(desync)
    }
}
