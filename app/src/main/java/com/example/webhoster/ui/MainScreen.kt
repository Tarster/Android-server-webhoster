package com.example.webhoster.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (state.screen) {
        Screen.Setup -> SetupScreen(viewModel, state)
        Screen.Folder -> FolderScreen(viewModel, state)
        Screen.Credentials -> CredentialsScreen(viewModel, state)
        Screen.Home -> HomeScreen(viewModel, state)
        Screen.Recover -> RecoverScreen(viewModel, state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(viewModel: MainViewModel, state: MainUiState) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Setup WebHoster") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Enter your relay address. Example: https://webhoster.tarster.com")
            OutlinedTextField(
                value = state.relayUrl,
                onValueChange = { viewModel.onRelayUrlChanged(it) },
                label = { Text("Relay URL") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.registrationToken,
                onValueChange = { viewModel.onTokenChanged(it) },
                label = { Text("Registration Token (Optional)") },
                modifier = Modifier.fillMaxWidth()
            )
            if (state.relayError != null) {
                Text(state.relayError, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { viewModel.validateAndRegister() },
                enabled = !state.isValidatingRelay,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isValidatingRelay) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                else Text("Register")
            }
            TextButton(onClick = { viewModel.navigateToRecover() }) {
                Text("Restore existing account")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(viewModel: MainViewModel, state: MainUiState) {
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onFolderSelected(it) }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onZipSelected(it) }
    }
    
    Scaffold(
        topBar = { TopAppBar(title = { Text("Select Website Content") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("How would you like to provide your website files?")
            
            Button(
                onClick = { treeLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Pick a Folder")
            }
            
            Text("— OR —", modifier = Modifier.align(Alignment.CenterHorizontally))
            
            Button(
                onClick = { zipLauncher.launch(arrayOf("application/zip")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isUnzipping
            ) {
                if (state.isUnzipping) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                else Text("Pick a .zip File")
            }
            
            if (state.relayError != null) {
                Text(state.relayError, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialsScreen(viewModel: MainViewModel, state: MainUiState) {
    val clipboard = LocalClipboardManager.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("Identity Created") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Important: Save your recovery code. It is shown only once.")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Recovery Code", style = MaterialTheme.typography.labelMedium)
                    Text(state.recoveryCode ?: "", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { clipboard.setText(AnnotatedString(state.recoveryCode ?: "")) }) {
                        Text("Copy Code")
                    }
                }
            }
            Button(onClick = { viewModel.onCredentialsSeen() }, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel, state: MainUiState) {
    val clipboard = LocalClipboardManager.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("WebHoster Online") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isServiceRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connection: ${if (state.isServiceRunning) "ACTIVE" else "STOPPED"}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Device ID: ${state.deviceId}")
                    Text("Public URL:", style = MaterialTheme.typography.labelMedium)
                    Text(state.publicUrl ?: "Not assigned", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(state.publicUrl ?: "")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy URL")
                    }
                }
            }
            
            Text("Note: Your site is hosted securely via the reverse tunnel.", style = MaterialTheme.typography.bodySmall)
            
            Button(
                onClick = { if (state.isServiceRunning) viewModel.stopTunnelService() else viewModel.startTunnelService() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isServiceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (state.isServiceRunning) "Stop Tunnel" else "Start Tunnel")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverScreen(viewModel: MainViewModel, state: MainUiState) {
    var code by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Recover Account") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Enter your recovery code to restore your device ID.")
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Recovery Code") },
                modifier = Modifier.fillMaxWidth()
            )
            if (state.relayError != null) {
                Text(state.relayError, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { viewModel.recover(code) },
                enabled = !state.isValidatingRelay,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isValidatingRelay) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                else Text("Recover")
            }
            TextButton(onClick = { viewModel.navigateToSetup() }) {
                Text("Back to Registration")
            }
        }
    }
}
