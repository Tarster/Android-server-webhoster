package com.example.webhoster.tunnel

import com.example.webhoster.model.TunnelFrame
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class TunnelClient(
    private val httpClient: OkHttpClient,
    private val deviceId: String,
    private val secret: String
) {
    private var webSocket: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val socketMutex = Mutex()
    
    // Per-request control channels
    private val windowCredits = ConcurrentHashMap<String, Channel<Int>>()
    private val cancellations = ConcurrentHashMap<String, Unit>()

    private val _status = MutableStateFlow(Status.DISCONNECTED)
    val status = _status.asStateFlow()

    private val _assignedUrl = MutableStateFlow<String?>(null)
    val assignedUrl = _assignedUrl.asStateFlow()

    private val _requests = MutableSharedFlow<TunnelFrame>(extraBufferCapacity = 100)
    val requests = _requests.asSharedFlow()

    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, AUTH_ERROR, RETRYING }

    fun connect(wsUrl: String) {
        val request = Request.Builder()
            .url(wsUrl)
            .addHeader("Authorization", "Bearer $deviceId:$secret")
            .build()

        _status.value = Status.CONNECTING

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = Status.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = json.decodeFromString<TunnelFrame>(text)
                    when (frame.type) {
                        "window" -> frame.credit?.let { windowCredits[frame.requestId]?.trySend(it) }
                        "cancel" -> {
                            cancellations[frame.requestId] = Unit
                            windowCredits[frame.requestId]?.close()
                        }
                        "request" -> {
                            if (frame.requestId == "welcome") {
                                val url = frame.headers?.get("X-Public-Url")
                                if (url != null) _assignedUrl.value = url
                            } else {
                                _requests.tryEmit(frame)
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 401) {
                    _status.value = Status.AUTH_ERROR
                } else {
                    _status.value = Status.RETRYING
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = Status.DISCONNECTED
            }
        })
    }

    fun registerStream(requestId: String): Channel<Int> {
        val channel = Channel<Int>(Channel.UNLIMITED)
        windowCredits[requestId] = channel
        return channel
    }

    fun unregisterStream(requestId: String) {
        windowCredits.remove(requestId)?.close()
        cancellations.remove(requestId)
    }

    fun isCancelled(requestId: String) = cancellations.containsKey(requestId)

    suspend fun sendFrame(frame: TunnelFrame) = socketMutex.withLock {
        webSocket?.send(json.encodeToString(frame))
    }

    suspend fun sendBinaryChunk(requestId: String, data: ByteArray) = socketMutex.withLock {
        val reqIdBytes = requestId.toByteArray(Charsets.US_ASCII)
        val buffer = ByteBuffer.allocate(1 + 1 + reqIdBytes.size + data.size)
        buffer.put(0x01.toByte())
        buffer.put(reqIdBytes.size.toByte())
        buffer.put(reqIdBytes)
        buffer.put(data)
        webSocket?.send(buffer.array().toByteString(0, buffer.position()))
    }

    fun disconnect() {
        webSocket?.close(1000, "User shutdown")
        webSocket = null
        _status.value = Status.DISCONNECTED
    }
}
