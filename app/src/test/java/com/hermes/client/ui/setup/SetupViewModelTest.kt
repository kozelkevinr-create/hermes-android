package com.hermes.client.ui.setup

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.network.GatedAuth
import com.hermes.client.data.network.GatewayProbeResult
import com.hermes.client.data.network.HermesRestApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    private val store = mockk<CredentialStore>(relaxed = true)
    private val rest = mockk<HermesRestApi>(relaxed = true)
    private val gatedAuth = mockk<GatedAuth>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.load() } returns null
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = SetupViewModel(store, rest, gatedAuth)

    // probeLogin runs on Dispatchers.IO in production, so resuming the state update needs the
    // test scheduler to keep draining while the real IO thread finishes.
    private suspend fun TestScope.awaitTestResult(vm: SetupViewModel): String? {
        repeat(200) {
            advanceUntilIdle()
            vm.state.value.testResult?.let { return it }
            Thread.sleep(5)
        }
        return vm.state.value.testResult
    }

    @Test fun test_connected_reports_connected() = runTest(dispatcher) {
        every { gatedAuth.probeLogin("https://h", "alice", "pw") } returns GatewayProbeResult.Connected
        val vm = vm()
        vm.onUrlChange("https://h")
        vm.onUsernameChange("alice")
        vm.onPasswordChange("pw")
        vm.test()
        advanceUntilIdle()
        assertEquals("Connected", awaitTestResult(vm))
    }

    @Test fun test_rejected_gated_login_reports_wrong_credentials() = runTest(dispatcher) {
        every {
            gatedAuth.probeLogin("https://h", "alice", "bad")
        } returns GatewayProbeResult.WrongCredentials
        val vm = vm()
        vm.onUrlChange("https://h")
        vm.onUsernameChange("alice")
        vm.onPasswordChange("bad")
        vm.test()
        advanceUntilIdle()
        assertEquals("Wrong username or password", awaitTestResult(vm))
    }

    @Test fun test_connection_failure_reports_unreachable() = runTest(dispatcher) {
        every {
            gatedAuth.probeLogin("https://h", "alice", "pw")
        } returns GatewayProbeResult.Unreachable
        val vm = vm()
        vm.onUrlChange("https://h")
        vm.onUsernameChange("alice")
        vm.onPasswordChange("pw")
        vm.test()
        advanceUntilIdle()
        assertEquals("Unreachable", awaitTestResult(vm))
    }

    @Test fun test_rejected_token_reports_token_rejected() = runTest(dispatcher) {
        coEvery { rest.statusFor("https://h", "bad-token") } returns GatewayProbeResult.TokenRejected
        val vm = vm()
        vm.onUrlChange("https://h")
        vm.onTokenChange("bad-token")
        vm.test()
        advanceUntilIdle()
        assertEquals("Token rejected", awaitTestResult(vm))
    }

    @Test fun applyPairing_populates_fields_from_valid_payload() {
        val vm = vm()
        vm.applyPairing("""{"v":1,"url":"https://h.ts.net","username":"a","password":"p"}""")
        val s = vm.state.value
        assertEquals("https://h.ts.net", s.url)
        assertEquals("a", s.username)
        assertEquals("p", s.password)
        assertNull(s.scanError)
    }

    @Test fun applyPairing_sets_scanError_and_leaves_fields_blank_on_garbage() {
        val vm = vm()
        vm.applyPairing("not a hermes code")
        val s = vm.state.value
        assertEquals("Not a Hermes pairing code", s.scanError)
        assertEquals("", s.url)
        assertEquals("", s.password)
    }

    @Test fun clearScanError_clears_it() {
        val vm = vm()
        vm.applyPairing("garbage")
        vm.clearScanError()
        assertNull(vm.state.value.scanError)
    }
}
