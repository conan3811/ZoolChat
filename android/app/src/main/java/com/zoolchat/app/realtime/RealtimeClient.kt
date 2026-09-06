package com.zoolchat.app.realtime

import com.zoolchat.app.BuildConfig
import com.zoolchat.app.auth.AuthRepository
import com.zoolchat.app.auth.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.random.Random

class RealtimeClient(
    private val client: OkHttpClient,
    private val auth: AuthRepository,
    private val session: SessionStore,
    private val onEvent: (JSONObject) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var socket: WebSocket? = null
    @Volatile private var targetUrl: String? = null
    private val closedByUser = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    @Volatile private var retryAttempt = 0

    fun connect(baseHttpUrl: String) {
        targetUrl = baseHttpUrl
        closedByUser.set(false)
        reconnectSoon(0)
    }

    private fun reconnectSoon(delayMs: Long) {
        if (closedByUser.get() || !connecting.compareAndSet(false, true)) return
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!isActive || closedByUser.get()) { connecting.set(false); return@launch }
            val base = targetUrl ?: run { connecting.set(false); return@launch }
            val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/') + "/ws"
            val request = Request.Builder().url(wsUrl).build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connecting.set(false)
                    retryAttempt = 0
                    scope.launch {
                        val payload = JSONObject().put("type", "auth")
                        val token = auth.bearerToken()
                        if (token != null) payload.put("token", token)
                        else if (BuildConfig.DEV_AUTH_ENABLED) payload.put("devUserId", session.devUserId)
                        webSocket.send(payload.toString())
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { onEvent(JSONObject(text)) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (socket === webSocket) socket = null
                    connecting.set(false)
                    if (!closedByUser.get()) reconnectSoon(nextBackoff())
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (socket === webSocket) socket = null
                    connecting.set(false)
                    if (!closedByUser.get()) reconnectSoon(nextBackoff())
                }
            })
        }
    }

    private fun nextBackoff(): Long {
        val exp = min(30_000L, 1_500L * (1L shl min(retryAttempt, 4)))
        retryAttempt = min(retryAttempt + 1, 10)
        return exp + Random.nextLong(0, 750)
    }

    fun close() {
        closedByUser.set(true)
        retryAttempt = 0
        socket?.close(1000, "bye")
        socket = null
        connecting.set(false)
    }
}
