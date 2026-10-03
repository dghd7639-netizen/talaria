package app.hermes.mobile.data

import app.hermes.mobile.pairing.DeviceConnection
import app.hermes.mobile.threads.MobileEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

open class BridgeEventSocket(
    private val connection: DeviceConnection,
    private val client: OkHttpClient,
    private val json: Json,
    private val onAuthenticationExpired: () -> Unit = {},
) {
    open fun connect(after: Long = 0, onEvent: (MobileEvent) -> Unit): EventConnection {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val closed = AtomicBoolean(false)
        val reconnecting = AtomicBoolean(false)
        val cursor = AtomicLong(after)
        var current: WebSocket? = null

        fun open() {
        val url = connection.baseUrl.newBuilder()
            .addPathSegments("v1/events")
                .addQueryParameter("after", cursor.get().toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${connection.deviceSecret}")
            .build()
            current = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val dto = json.decodeFromString<EventDto>(text)
                    cursor.updateAndGet { previous -> maxOf(previous, dto.id) }
                onEvent(
                    MobileEvent(
                        dto.id,
                        dto.threadId,
                        dto.type,
                        dto.payload.mapValues { (_, value) ->
                            (value as? JsonPrimitive)?.content.orEmpty()
                        },
                    ),
                )
            }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (isAuthenticationExpired(response?.code)) expireAuthentication() else scheduleReconnect()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    if (isAuthenticationExpired(code)) expireAuthentication() else scheduleReconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (isAuthenticationExpired(code)) expireAuthentication() else scheduleReconnect()
                }

                private fun expireAuthentication() {
                    if (!closed.compareAndSet(false, true)) return
                    scope.cancel()
                    onAuthenticationExpired()
                }

                private fun scheduleReconnect() {
                    if (closed.get() || !reconnecting.compareAndSet(false, true)) return
                    scope.launch {
                        delay(1_000)
                        reconnecting.set(false)
                        if (!closed.get()) open()
                    }
                }
            })
        }

        open()
        return EventConnection {
            closed.set(true)
            scope.cancel()
            current?.cancel()
        }
    }

}

internal fun isAuthenticationExpired(code: Int?): Boolean = code == 401 || code == 403 || code == 4401

fun interface EventConnection : Closeable {
    override fun close()
}

@Serializable
private data class EventDto(
    val id: Long,
    @SerialName("thread_id") val threadId: String,
    val type: String,
    val payload: kotlinx.serialization.json.JsonObject,
)
