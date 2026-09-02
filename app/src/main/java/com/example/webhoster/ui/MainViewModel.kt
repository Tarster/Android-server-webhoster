package com.example.webhoster.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.webhoster.server.TunnelService
import com.example.webhoster.storage.FileManager
import com.example.webhoster.tunnel.RelayRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class MainUiState(
    val screen: Screen = Screen.Setup,
    val relayUrl: String = "https://webhoster.tarster.com",
    val registrationToken: String = "",
    val isValidatingRelay: Boolean = false,
    val relayError: String? = null,
    
    val selectedFolderUri: Uri? = null,
    val isUsingLocalDir: Boolean = false,
    val isUnzipping: Boolean = false,
    val selectedFolderName: String? = null,
    
    val deviceId: String? = null,
    val publicUrl: String? = null,
    val recoveryCode: String? = null,
    
    val isServiceRunning: Boolean = false,
    val statusMessage: String = "Offline"
)

enum class Screen { Setup, Folder, Credentials, Home, Recover }

class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val repo: RelayRepository = RelayRepository(application, OkHttpClient()),
    private val fileManager: FileManager = FileManager(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        val registered = repo.isRegistered()
        val prefs = application.getSharedPreferences("webhoster_prefs", android.content.Context.MODE_PRIVATE)
        val folderUri = prefs.getString("folder_uri", null)
        val isUsingLocal = prefs.getBoolean("is_using_local", false)
        
        if (registered && (folderUri != null || isUsingLocal)) {
            _uiState.update { it.copy(
                screen = Screen.Home,
                deviceId = repo.getDeviceId(),
                publicUrl = repo.getPublicUrl(),
                selectedFolderUri = folderUri?.let { u -> Uri.parse(u) },
                isUsingLocalDir = isUsingLocal,
                selectedFolderName = if (isUsingLocal) "Last Unzipped Content" else folderUri?.let { u -> Uri.parse(u).lastPathSegment }
            ) }
            startTunnelService()
        } else if (registered) {
            _uiState.update { it.copy(screen = Screen.Folder, deviceId = repo.getDeviceId()) }
        }

        // Observe live URL from service
        viewModelScope.launch {
            TunnelService.sessionUrl.collect { url ->
                if (url != null) {
                    _uiState.update { it.copy(publicUrl = url) }
                }
            }
        }
    }

    fun onRelayUrlChanged(url: String) {
        _uiState.update { it.copy(relayUrl = url, relayError = null) }
    }

    fun onTokenChanged(token: String) {
        _uiState.update { it.copy(registrationToken = token) }
    }

    fun validateAndRegister() {
        viewModelScope.launch {
            _uiState.update { it.copy(isValidatingRelay = true, relayError = null) }
            val healthy = repo.checkHealth(_uiState.value.relayUrl)
            if (!healthy) {
                _uiState.update { it.copy(isValidatingRelay = false, relayError = "Relay not found or unhealthy") }
                return@launch
            }

            val response = repo.register(_uiState.value.relayUrl, _uiState.value.registrationToken.ifBlank { null })
            if (response != null) {
                _uiState.update { it.copy(
                    isValidatingRelay = false,
                    screen = Screen.Credentials,
                    deviceId = response.deviceId,
                    publicUrl = response.url,
                    recoveryCode = response.recoveryCode
                ) }
            } else {
                _uiState.update { it.copy(isValidatingRelay = false, relayError = "Registration failed") }
            }
        }
    }

    fun onFolderSelected(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
            
            getApplication<Application>().getSharedPreferences("webhoster_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("folder_uri", uri.toString())
                .putBoolean("is_using_local", false)
                .apply()
            
            _uiState.update { it.copy(
                selectedFolderUri = uri,
                isUsingLocalDir = false,
                selectedFolderName = uri.lastPathSegment,
                screen = Screen.Home
            ) }
            startTunnelService()
        } catch (e: Exception) {}
    }

    fun onZipSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUnzipping = true, relayError = null) }
            try {
                fileManager.unzipToInternalStorage(uri)
                
                getApplication<Application>().getSharedPreferences("webhoster_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .remove("folder_uri")
                    .putBoolean("is_using_local", true)
                    .apply()

                _uiState.update { it.copy(
                    isUnzipping = false,
                    isUsingLocalDir = true,
                    selectedFolderUri = null,
                    selectedFolderName = "Unzipped: ${uri.lastPathSegment}",
                    screen = Screen.Home
                ) }
                startTunnelService()
            } catch (e: Exception) {
                _uiState.update { it.copy(isUnzipping = false, relayError = "Unzip failed: ${e.message}") }
            }
        }
    }

    fun startTunnelService() {
        val intent = Intent(getApplication(), TunnelService::class.java)
        if (_uiState.value.isUsingLocalDir) {
            intent.putExtra("USE_LOCAL_DIR", true)
        } else {
            val uri = _uiState.value.selectedFolderUri ?: return
            intent.putExtra("TREE_URI", uri.toString())
        }
        getApplication<Application>().startForegroundService(intent)
        _uiState.update { it.copy(isServiceRunning = true) }
    }

    fun stopTunnelService() {
        val intent = Intent(getApplication(), TunnelService::class.java).apply { action = "STOP" }
        getApplication<Application>().startService(intent)
        _uiState.update { it.copy(isServiceRunning = false) }
    }

    fun navigateToRecover() {
        _uiState.update { it.copy(screen = Screen.Recover) }
    }

    fun navigateToSetup() {
        _uiState.update { it.copy(screen = Screen.Setup) }
    }
    
    fun onCredentialsSeen() {
        _uiState.update { it.copy(screen = Screen.Folder) }
    }

    fun recover(recoveryCode: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isValidatingRelay = true, relayError = null) }
            val healthy = repo.checkHealth(_uiState.value.relayUrl)
            if (!healthy) {
                _uiState.update { it.copy(isValidatingRelay = false, relayError = "Relay not found or unhealthy") }
                return@launch
            }

            val response = repo.recover(_uiState.value.relayUrl, recoveryCode)
            if (response != null) {
                _uiState.update { it.copy(
                    isValidatingRelay = false,
                    screen = Screen.Credentials,
                    deviceId = response.deviceId,
                    publicUrl = response.url,
                    recoveryCode = response.recoveryCode
                ) }
            } else {
                _uiState.update { it.copy(isValidatingRelay = false, relayError = "Recovery failed") }
            }
        }
    }
}
