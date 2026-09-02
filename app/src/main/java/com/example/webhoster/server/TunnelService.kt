package com.example.webhoster.server

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.webhoster.MainActivity
import com.example.webhoster.model.TunnelFrame
import com.example.webhoster.storage.FileManager
import com.example.webhoster.tunnel.RelayRepository
import com.example.webhoster.tunnel.TunnelClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import okhttp3.OkHttpClient
import java.io.File
import java.net.URLConnection
import androidx.core.net.toUri

class TunnelService : Service() {

    companion object {
        private val _sessionUrl = MutableStateFlow<String?>(null)
        val sessionUrl = _sessionUrl.asStateFlow()
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var repo: RelayRepository
    private lateinit var fileManager: FileManager
    private var tunnelClient: TunnelClient? = null
    
    private val CHANNEL_ID = "tunnel_service"

    override fun onCreate() {
        super.onCreate()
        repo = RelayRepository(this, OkHttpClient())
        fileManager = FileManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val treeUriStr = intent?.getStringExtra("TREE_URI")
        val useLocalDir = intent?.getBooleanExtra("USE_LOCAL_DIR", false) ?: false
        val treeUri = treeUriStr?.toUri()

        startForeground(1, createNotification("Connecting..."))

        scope.launch {
            val wsUrl = repo.getWebsocketUrl() ?: return@launch
            val deviceId = repo.getDeviceId() ?: return@launch
            val secret = repo.getSecret() ?: return@launch
            val publicUrl = repo.getPublicUrl() ?: ""

            tunnelClient = TunnelClient(OkHttpClient(), deviceId, secret).apply {
                connect(wsUrl)
                
                launch {
                    assignedUrl.collect { url ->
                        _sessionUrl.value = url
                    }
                }

                launch {
                    status.collectLatest { s ->
                        updateNotification(when(s) {
                            TunnelClient.Status.CONNECTED -> "Online: $publicUrl"
                            TunnelClient.Status.CONNECTING -> "Connecting..."
                            TunnelClient.Status.RETRYING -> "Retrying..."
                            TunnelClient.Status.AUTH_ERROR -> "Auth Error!"
                            else -> "Offline"
                        })
                    }
                }

                launch {
                    requests.collect { frame ->
                        launch { handleRequest(frame, treeUri, useLocalDir) }
                    }
                }
            }
        }

        return START_STICKY
    }

    private suspend fun handleRequest(frame: TunnelFrame, treeUri: Uri?, useLocalDir: Boolean) {
        val client = tunnelClient ?: return
        val requestId = frame.requestId
        val creditChannel = client.registerStream(requestId)
        
        try {
            val path = frame.path ?: "/"
            
            val resource = if (useLocalDir) {
                val localDir = File(filesDir, "www")
                val localFile = fileManager.findFileInLocalDir(localDir, path)
                if (localFile != null && localFile.exists()) {
                    Triple(localFile.inputStream(), URLConnection.guessContentTypeFromName(localFile.name) ?: "application/octet-stream", localFile.length())
                } else null
            } else if (treeUri != null) {
                val docFile = fileManager.findFileInTree(treeUri, path)
                if (docFile != null && docFile.exists()) {
                    Triple(fileManager.openStream(docFile), fileManager.getMimeType(docFile), docFile.length())
                } else null
            } else null
            
            if (resource == null || resource.first == null) {
                client.sendFrame(TunnelFrame("head", requestId, statusCode = 404))
                client.sendFrame(TunnelFrame("end", requestId))
                return
            }

            val (inputStream, mime, len) = resource

            client.sendFrame(TunnelFrame("head", requestId, statusCode = 200, headers = mapOf(
                "Content-Type" to mime,
                "Content-Length" to len.toString()
            )))

            inputStream?.use { input ->
                var available = frame.window ?: 524288
                val buffer = ByteArray(64 * 1024) // 64KiB chunks

                while (true) {
                    if (client.isCancelled(requestId)) break
                    
                    // Flow control: wait for credit if window is small
                    while (available < buffer.size) {
                        val newCredit = creditChannel.receive()
                        available += newCredit
                    }

                    val read = input.read(buffer)
                    if (read == -1) break
                    
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    client.sendBinaryChunk(requestId, chunk)
                    available -= read
                }
            }
            
            if (!client.isCancelled(requestId)) {
                client.sendFrame(TunnelFrame("end", requestId))
            }
        } catch (e: Exception) {
            client.sendFrame(TunnelFrame("error", requestId, message = e.message))
        } finally {
            client.unregisterStream(requestId)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebHoster")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, createNotification(text))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Tunnel Status", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        tunnelClient?.disconnect()
        _sessionUrl.value = null
        job.cancel()
        super.onDestroy()
    }
}
