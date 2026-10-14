package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.Application
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlannedLocalRuntimeRecoveryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private class Fixture(scope: CoroutineScope) {
        var available = 3L * 1024 * 1024 * 1024
        var features = AppFeatureSettings()
        val native = FakeLocalRuntime()
        val nativeState = MutableStateFlow(LocalRuntimeState())
        private val delegate = object : LocalRuntime by native {
            override val state = nativeState
            override suspend fun loadEngine(spec: LocalEngineSpec) {
                native.loadEngine(spec)
                nativeState.value = nativeState.value.copy(engineSpec = native.loadedEngineSpec(), lifecycle = "READY")
            }
        }
        private val settings = mockk<SettingRepository>().also { repository ->
            coEvery { repository.getFeatureSettings() } answers { features }
        }
        private val store = mockk<BenchmarkStore>(relaxed = true).also {
            every { it.runtimeQualifications } returns MutableStateFlow(emptyList())
        }
        val runtime = PlannedLocalRuntime(RuntimeEnvironment.getApplication(), delegate, settings, store, availableMemory = { available }, stateScope = scope)
    }

    private fun spec(): LocalEngineSpec = LocalEngineSpec(
        temporaryFolder.newFile("recovery.litertlm").apply { writeText("test artifact") }.path,
        "cpu",
        1024,
        tuningLocked = true
    )

    @Test fun rejectedLoadRecoversAfterReserveResetWithoutChangingTheContext() = runTest {
        val fixture = Fixture(backgroundScope)
        val spec = spec()
        fixture.features = AppFeatureSettings(localMemoryReserveMb = 4096)
        val failure = runCatching { fixture.runtime.loadEngine(spec) }.exceptionOrNull()
        assertTrue(failure is LocalMemoryAdmissionException)
        assertTrue(failure?.message.orEmpty().contains("nativeLoadAttempted=false"))
        assertTrue(fixture.native.loadEngineCalls.isEmpty())
        assertEquals("FAILED", fixture.runtime.state.value.lifecycle)
        fixture.features = AppFeatureSettings(localMemoryReserveMb = 768)
        fixture.runtime.loadEngine(spec)
        assertEquals(1, fixture.native.loadEngineCalls.size)
        assertEquals(1024, fixture.runtime.loadedEngineSpec()?.maxTokens)
        assertEquals("READY", fixture.runtime.state.value.lifecycle)
    }

    @Test fun generationRejectionIsTypedAndRecoveryReusesTheWarmEngine() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.runtime.loadEngine(spec())
        fixture.available = 100L * 1024 * 1024
        val failure = runCatching { fixture.runtime.sendMessage("Hello").toList() }.exceptionOrNull()
        assertTrue(failure is LocalMemoryAdmissionException)
        assertTrue(failure?.message.orEmpty().contains("stage=generate"))
        assertTrue(failure?.message.orEmpty().contains("nativeLoadAttempted=true"))
        assertTrue(fixture.native.sendMessageCalls.isEmpty())
        fixture.available = 3L * 1024 * 1024 * 1024
        fixture.native.scriptedEvents = listOf(listOf(LocalRuntimeEvent.TextDelta("Hello"), LocalRuntimeEvent.Done))
        val events = fixture.runtime.sendMessage("Hello").toList()
        assertTrue(events.any { it is LocalRuntimeEvent.TextDelta })
        assertEquals(1, fixture.native.loadEngineCalls.size)
        assertEquals(1, fixture.native.sendMessageCalls.size)
        assertFalse(fixture.runtime.state.value.fallbackReason.orEmpty().contains("MEMORY_ADMISSION_REJECTED"))
    }

    @Test fun delayedDelegateStateCannotEraseAnAdmissionFailure() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.available = 0
        runCatching { fixture.runtime.loadEngine(spec()) }
        fixture.nativeState.value = LocalRuntimeState(lifecycle = "IDLE", fallbackReason = "late delegate update")
        runCurrent()
        assertEquals("FAILED", fixture.runtime.state.value.lifecycle)
        assertTrue(fixture.runtime.state.value.fallbackReason.orEmpty().contains("MEMORY_ADMISSION_REJECTED"))
    }

    @Test fun simultaneousHeatAndLowMemoryReportHeatWithoutMisleadingReserveAdvice() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.available = 0
        fixture.native.simulatedHardwareState = DeviceHardwareState(thermalState = DeviceThermalState.SEVERE)
        val failure = runCatching { fixture.runtime.loadEngine(spec()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(failure is LocalMemoryAdmissionException)
        assertTrue(failure?.message.orEmpty().contains("THERMAL_ADMISSION_REJECTED"))
        assertTrue(fixture.native.loadEngineCalls.isEmpty())
    }
}
