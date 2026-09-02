package com.example.webhoster.tunnel

import app.cash.turbine.test
import com.example.webhoster.model.TunnelFrame
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class TunnelClientTest {

    private lateinit var httpClient: OkHttpClient
    private lateinit var webSocket: WebSocket
    private lateinit var tunnelClient: TunnelClient
    private val deviceId = "test-device"
    private val secret = "test-secret"

    @Before
    fun setup() {
        httpClient = mockk(relaxed = true)
        webSocket = mockk(relaxed = true)
        tunnelClient = TunnelClient(httpClient, deviceId, secret)
    }

    @Test
    fun `connect should initialize websocket with correct headers`() {
        val wsUrl = "ws://localhost:8080/_ws"
        val requestSlot = slot<Request>()
        
        every { httpClient.newWebSocket(capture(requestSlot), any()) } returns webSocket

        tunnelClient.connect(wsUrl)

        verify { httpClient.newWebSocket(any(), any()) }
        // OkHttp normalizes ws:// to http:// internally
        assertEquals("http://localhost:8080/_ws", requestSlot.captured.url.toString())
        assertEquals("Bearer $deviceId:$secret", requestSlot.captured.header("Authorization"))
        assertEquals(TunnelClient.Status.CONNECTING, tunnelClient.status.value)
    }

    @Test
    fun `welcome frame should update assignedUrl`() = runTest {
        val wsUrl = "ws://localhost:8080/_ws"
        val listenerSlot = slot<WebSocketListener>()
        val publicUrl = "https://test.webhoster.tarster.com/"
        
        every { httpClient.newWebSocket(any(), capture(listenerSlot)) } returns webSocket
        tunnelClient.connect(wsUrl)

        val welcomeFrame = """
            {
              "type": "request",
              "requestId": "welcome",
              "method": "CONNECT",
              "path": "/test-device",
              "headers": {
                "X-Public-Url": "$publicUrl"
              }
            }
        """.trimIndent()

        listenerSlot.captured.onMessage(webSocket, welcomeFrame)

        assertEquals(publicUrl, tunnelClient.assignedUrl.value)
    }

    @Test
    fun `request frame should emit to requests flow`() = runTest {
        val wsUrl = "ws://localhost:8080/_ws"
        val listenerSlot = slot<WebSocketListener>()
        
        every { httpClient.newWebSocket(any(), capture(listenerSlot)) } returns webSocket
        tunnelClient.connect(wsUrl)

        val requestFrame = """
            {
              "type": "request",
              "requestId": "req-123",
              "method": "GET",
              "path": "/index.html"
            }
        """.trimIndent()

        tunnelClient.requests.test {
            listenerSlot.captured.onMessage(webSocket, requestFrame)
            val result = awaitItem()
            assertEquals("req-123", result.requestId)
            assertEquals("GET", result.method)
            assertEquals("/index.html", result.path)
        }
    }

    @Test
    fun `window frame should update credit channel`() = runTest {
        val wsUrl = "ws://localhost:8080/_ws"
        val listenerSlot = slot<WebSocketListener>()
        
        every { httpClient.newWebSocket(any(), capture(listenerSlot)) } returns webSocket
        tunnelClient.connect(wsUrl)

        val requestId = "req-123"
        val creditChannel = tunnelClient.registerStream(requestId)

        val windowFrame = """
            {
              "type": "window",
              "requestId": "$requestId",
              "credit": 1024
            }
        """.trimIndent()

        listenerSlot.captured.onMessage(webSocket, windowFrame)

        val credit = creditChannel.receive()
        assertEquals(1024, credit)
        
        tunnelClient.unregisterStream(requestId)
    }

    @Test
    fun `cancel frame should close credit channel and set cancelled state`() = runTest {
        val wsUrl = "ws://localhost:8080/_ws"
        val listenerSlot = slot<WebSocketListener>()
        
        every { httpClient.newWebSocket(any(), capture(listenerSlot)) } returns webSocket
        tunnelClient.connect(wsUrl)

        val requestId = "req-123"
        val creditChannel = tunnelClient.registerStream(requestId)

        val cancelFrame = """
            {
              "type": "cancel",
              "requestId": "$requestId"
            }
        """.trimIndent()

        listenerSlot.captured.onMessage(webSocket, cancelFrame)

        assertTrue(tunnelClient.isCancelled(requestId))
        assertTrue(creditChannel.isClosedForReceive)
        
        tunnelClient.unregisterStream(requestId)
    }
}
