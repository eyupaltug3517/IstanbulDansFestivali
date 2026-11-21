package com.nesemu.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A broadcast room: every message sent reaches the other members. */
interface Transport {
    interface Listener {
        fun onJoined()
        fun onMessage(event: String, payload: JSONObject)
        fun onError(message: String)
    }

    fun connect(room: String, listener: Listener)
    fun send(event: String, payload: JSONObject)
    fun close()
}

/**
 * Supabase Realtime "broadcast" channel over the Phoenix WebSocket protocol.
 * No database tables are used; the channel name is the room.
 */
class SupabaseTransport(
    private val projectUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = sharedClient,
) : Transport {
    private var socket: WebSocket? = null
    private var topic = ""
    private val ref = AtomicInteger(1)
    private var heartbeat: ScheduledExecutorService? = null
    @Volatile private var closed = false

    override fun connect(room: String, listener: Transport.Listener) {
        topic = "realtime:retrosalon-$room"
        val wsUrl = projectUrl.replace("https://", "wss://").trimEnd('/') +
            "/realtime/v1/websocket?apikey=$apiKey&vsn=1.0.0"
        val request = Request.Builder().url(wsUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val join = JSONObject()
                    .put("topic", topic)
                    .put("event", "phx_join")
                    .put("ref", ref.getAndIncrement().toString())
                    .put("payload", JSONObject().put("config", JSONObject()
                        .put("broadcast", JSONObject().put("self", false).put("ack", false))
                        .put("presence", JSONObject().put("key", ""))
                        .put("private", false)))
                webSocket.send(join.toString())
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: Exception) { return }
                val event = msg.optString("event")
                when {
                    event == "phx_reply" && msg.optString("topic") == topic -> {
                        val status = msg.optJSONObject("payload")?.optString("status")
                        if (status == "ok") listener.onJoined()
                        else listener.onError("Odaya bağlanılamadı ($status)")
                    }
                    event == "broadcast" -> {
                        val p = msg.optJSONObject("payload") ?: return
                        listener.onMessage(p.optString("event"), p.optJSONObject("payload") ?: JSONObject())
                    }
                    event == "phx_error" -> listener.onError("Bağlantı hatası")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closed) listener.onError("İnternet bağlantısı koptu (${t.message ?: t.javaClass.simpleName})")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closed) listener.onError("Bağlantı kapandı")
            }
        })
    }

    private fun startHeartbeat(ws: WebSocket) {
        heartbeat = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "realtime-heartbeat").apply { isDaemon = true } }.also {
            it.scheduleAtFixedRate({
                ws.send(JSONObject().put("topic", "phoenix").put("event", "heartbeat")
                    .put("payload", JSONObject()).put("ref", ref.getAndIncrement().toString()).toString())
            }, 20, 20, TimeUnit.SECONDS)
        }
    }

    override fun send(event: String, payload: JSONObject) {
        val msg = JSONObject()
            .put("topic", topic)
            .put("event", "broadcast")
            .put("ref", JSONObject.NULL)
            .put("payload", JSONObject().put("type", "broadcast").put("event", event).put("payload", payload))
        socket?.send(msg.toString())
    }

    override fun close() {
        closed = true
        heartbeat?.shutdownNow()
        socket?.close(1000, "bye")
        socket = null
    }

    companion object {
        val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
        }
    }
}
