package com.nesemu.net

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Talks to the real Supabase project. Opt-in: NES_LIVE_NET=1. */
class SupabaseLiveTest {
    @Test
    fun twoClientsExchangeMessages() {
        assumeTrue(System.getenv("NES_LIVE_NET") != null)
        val room = "test" + (1000..9999).random()
        val joined = CountDownLatch(2)
        val got = CountDownLatch(20)
        val rtts = ArrayList<Long>()
        val a = SupabaseTransport(NetConfig.PROJECT_URL, NetConfig.API_KEY)
        val b = SupabaseTransport(NetConfig.PROJECT_URL, NetConfig.API_KEY)
        a.connect(room, object : Transport.Listener {
            override fun onJoined() { joined.countDown() }
            override fun onMessage(event: String, payload: JSONObject) {
                if (event == "pong") synchronized(rtts) { rtts.add(System.nanoTime() / 1_000_000 - payload.getLong("t")); got.countDown() }
            }
            override fun onError(message: String) { println("A error: $message") }
        })
        b.connect(room, object : Transport.Listener {
            override fun onJoined() { joined.countDown() }
            override fun onMessage(event: String, payload: JSONObject) {
                if (event == "ping") b.send("pong", payload)
            }
            override fun onError(message: String) { println("B error: $message") }
        })
        assertTrue("join", joined.await(15, TimeUnit.SECONDS))
        Thread.sleep(500)
        repeat(20) {
            a.send("ping", JSONObject().put("t", System.nanoTime() / 1_000_000))
            Thread.sleep(100)
        }
        val ok = got.await(10, TimeUnit.SECONDS)
        println("RTT ms: ${rtts.sorted()}")
        a.close(); b.close()
        assertTrue("received ${rtts.size} pongs", ok)
    }
}
