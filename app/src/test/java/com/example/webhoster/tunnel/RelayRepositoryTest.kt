package com.example.webhoster.tunnel

import android.content.Context
import android.content.SharedPreferences
import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RelayRepositoryTest {

    private lateinit var context: Context
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var relayRepository: RelayRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        okHttpClient = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        
        relayRepository = RelayRepository(context, okHttpClient, sharedPreferences)
    }

    @Test
    fun `checkHealth should return true when response is ok`() = runTest {
        val request = Request.Builder().url("https://relay.com/_health").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("ok".toResponseBody("text/plain".toMediaType()))
            .build()
            
        val call = mockk<okhttp3.Call>()
        every { call.execute() } returns response
        every { okHttpClient.newCall(any()) } returns call

        val result = relayRepository.checkHealth("https://relay.com")

        assertTrue("Health check should return true", result)
    }

    @Test
    fun `register should save credentials and return response`() = runTest {
        val jsonRes = """
            {
              "deviceId": "dev-1",
              "secret": "sec-1",
              "url": "https://dev-1.relay.com",
              "websocketUrl": "wss://relay.com/_ws"
            }
        """.trimIndent()
        
        val request = Request.Builder().url("https://relay.com/_register").build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(jsonRes.toResponseBody("application/json".toMediaType()))
            .build()

        val call = mockk<okhttp3.Call>()
        every { call.execute() } returns response
        every { okHttpClient.newCall(any()) } returns call

        val result = relayRepository.register("https://relay.com", "token123")

        assertNotNull("Registration result should not be null", result)
        assertEquals("dev-1", result?.deviceId)
        verify { 
            editor.putString("deviceId", "dev-1")
            editor.putString("secret", "sec-1")
        }
    }

    @Test
    fun `isRegistered should return true when deviceId and secret exist`() {
        every { sharedPreferences.getString("deviceId", null) } returns "dev-1"
        every { sharedPreferences.getString("secret", null) } returns "sec-1"

        assertTrue(relayRepository.isRegistered())
    }
}
