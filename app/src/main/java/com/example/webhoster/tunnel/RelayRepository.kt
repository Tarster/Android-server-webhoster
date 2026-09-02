package com.example.webhoster.tunnel

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.webhoster.model.RelayResponse
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RelayRepository(
    private val context: Context, 
    private val client: OkHttpClient,
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "relay_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkHealth(relayBaseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val url = if (relayBaseUrl.startsWith("http")) relayBaseUrl else "https://$relayBaseUrl"
        val request = Request.Builder().url("${url.trimEnd('/')}/_health").build()
        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful && response.body?.string()?.trim() == "ok"
            }
        } catch (e: Exception) { false }
    }

    suspend fun register(relayBaseUrl: String, token: String?): RelayResponse? = withContext(Dispatchers.IO) {
        val baseUrl = if (relayBaseUrl.startsWith("http")) relayBaseUrl else "https://$relayBaseUrl"
        val url = if (token != null) "${baseUrl.trimEnd('/')}/_register?token=$token" else "${baseUrl.trimEnd('/')}/_register"
        val body = "{}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseText = response.body?.string() ?: ""
                    val relayRes = json.decodeFromString<RelayResponse>(responseText)
                    saveCreds(relayRes)
                    relayRes
                } else null
            }
        } catch (e: Exception) { null }
    }

    suspend fun recover(relayBaseUrl: String, recoveryCode: String): RelayResponse? = withContext(Dispatchers.IO) {
        val url = "${relayBaseUrl.trimEnd('/')}/_recover"
        val body = "{\"recoveryCode\": \"$recoveryCode\"}".toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val relayRes = json.decodeFromString<RelayResponse>(response.body?.string() ?: "")
                    saveCreds(relayRes)
                    relayRes
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun saveCreds(res: RelayResponse) {
        prefs.edit().apply {
            putString("deviceId", res.deviceId)
            putString("secret", res.secret)
            putString("websocketUrl", res.websocketUrl)
            putString("publicUrl", res.url)
            apply()
        }
    }

    fun getDeviceId() = prefs.getString("deviceId", null)
    fun getSecret() = prefs.getString("secret", null)
    fun getWebsocketUrl() = prefs.getString("websocketUrl", null)
    fun getPublicUrl() = prefs.getString("publicUrl", null)
    fun isRegistered() = getDeviceId() != null && getSecret() != null
}
