package com.example.webhoster.ui

import android.app.Application
import android.content.SharedPreferences
import com.example.webhoster.storage.FileManager
import com.example.webhoster.tunnel.RelayRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private lateinit var application: Application
    private lateinit var repo: RelayRepository
    private lateinit var fileManager: FileManager
    private lateinit var prefs: SharedPreferences
    private lateinit var viewModel: MainViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        application = mockk(relaxed = true)
        repo = mockk(relaxed = true)
        fileManager = mockk(relaxed = true)
        prefs = mockk(relaxed = true)

        every { application.getSharedPreferences(any(), any()) } returns prefs
        every { repo.isRegistered() } returns false
        
        viewModel = MainViewModel(application, repo, fileManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state should be Setup when not registered`() {
        val uiState = viewModel.uiState.value
        assertEquals(Screen.Setup, uiState.screen)
    }

    @Test
    fun `onRelayUrlChanged should update state`() {
        val newUrl = "https://new.relay.com"
        viewModel.onRelayUrlChanged(newUrl)
        
        assertEquals(newUrl, viewModel.uiState.value.relayUrl)
        assertNull(viewModel.uiState.value.relayError)
    }

    @Test
    fun `validateAndRegister should navigate to Credentials on success`() = runTest {
        val relayUrl = "https://relay.com"
        viewModel.onRelayUrlChanged(relayUrl)
        
        coEvery { repo.checkHealth(relayUrl) } returns true
        coEvery { repo.register(relayUrl, null) } returns mockk {
            every { deviceId } returns "dev-123"
            every { url } returns "https://dev-123.relay.com"
            every { recoveryCode } returns "ABCD-1234"
        }

        viewModel.validateAndRegister()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(Screen.Credentials, state.screen)
        assertEquals("dev-123", state.deviceId)
        assertEquals("ABCD-1234", state.recoveryCode)
    }

    @Test
    fun `validateAndRegister should show error on health check failure`() = runTest {
        val relayUrl = "https://relay.com"
        viewModel.onRelayUrlChanged(relayUrl)
        
        coEvery { repo.checkHealth(relayUrl) } returns false

        viewModel.validateAndRegister()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(Screen.Setup, state.screen)
        assertEquals("Relay not found or unhealthy", state.relayError)
    }
}
