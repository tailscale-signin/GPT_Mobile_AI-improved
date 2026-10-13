package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.SpeculativeDecodingMode
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlannedLocalRuntimeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun modelPath(name: String): String = temporaryFolder.newFile(name).apply { writeText("test artifact") }.path

    private fun planned(delegate: LocalRuntime): PlannedLocalRuntime {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSystemService(name: String): Any? = if (name == Context.ACTIVITY_SERVICE) null else super.getSystemService(name)
        }
        val settings = mockk<SettingRepository>()
        coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(localSpeculativeDecoding = SpeculativeDecodingMode.ON)
        val evidence = mockk<BenchmarkStore>(relaxed = true)
        every { evidence.runtimeQualifications } returns MutableStateFlow(emptyList())
        return PlannedLocalRuntime(context, delegate, settings, evidence)
    }

    @Test fun globalOnUsesOrdinaryDecodingAndReusesTheSameNpuPackage() = runTest {
        val native = FakeLocalRuntime().apply {
            capabilities = LocalModelCapabilities(backends = setOf("npu"), speculative = false)
            failLoadEngineIf = { spec -> runCatching { capabilities!!.validate(spec) }.exceptionOrNull() }
        }
        val runtime = planned(native)
        val spec = LocalEngineSpec(modelPath("gemma-4-e2b-it_qualcomm_sm8750.litertlm"), "npu", 1024)
        runtime.loadEngine(spec)
        runtime.loadEngine(spec)
        assertEquals(1, native.loadEngineCalls.size)
        assertEquals("npu", runtime.loadedEngineSpec()?.accelerator)
        assertEquals(false, runtime.loadedEngineSpec()?.speculativeDecoding)
        assertTrue(runtime.state.value.fallbackReason.orEmpty().contains("ordinary decoding"))
        runtime.createConversation(LocalConversationConfig(LocalSamplerConfig(40, .95f, .8f), null, emptyList()))
        assertEquals(false, native.createConversationCalls.single().speculativeDecoding)
    }

    @Test fun supportedAndExplicitBenchmarkConfigurationsKeepTheirRequestedMode() = runTest {
        val native = FakeLocalRuntime().apply { capabilities = LocalModelCapabilities(speculative = true) }
        val runtime = planned(native)
        val spec = LocalEngineSpec(modelPath("supported.litertlm"), "gpu", 1024)
        runtime.loadEngine(spec)
        assertEquals(true, native.loadEngineCalls.single().speculativeDecoding)
        runtime.loadEngine(spec.copy(speculativeDecoding = false, tuningLocked = true))
        assertEquals(false, native.loadEngineCalls.last().speculativeDecoding)
        assertNull(runtime.state.value.fallbackReason)
    }

    @Test fun unknownSupportNeverForcesOnAndExplicitOffOrDefaultRemainUnchanged() {
        assertEquals(false, compatibleSpeculativeDecoding(true, null))
        assertEquals(false, compatibleSpeculativeDecoding(true, LocalModelCapabilities()))
        assertEquals(false, compatibleSpeculativeDecoding(false, LocalModelCapabilities(speculative = true)))
        assertNull(compatibleSpeculativeDecoding(null, LocalModelCapabilities(speculative = false)))
    }

    @Test fun explicitUnsupportedModeIsAConfigurationErrorAndRemoteNpuErrorsAreNotWrappedAgain() {
        val spec = LocalEngineSpec("npu.litertlm", "npu", 1024, speculativeDecoding = true, tuningLocked = true)
        assertThrows(LocalModelConfigurationException::class.java) { LocalModelCapabilities(speculative = false).validate(spec) }
        val message = LocalNpuPackageException(IllegalStateException("native cause")).message!!
        assertEquals(message, LocalNpuPackageException.fromRemote(message).message)
        assertFalse(message.contains("NPU initialization failed: NPU initialization failed"))
    }

    @Test fun samePathWithChangedArtifactCannotReuseWarmQualification() = runTest {
        val native = FakeLocalRuntime().apply { capabilities = LocalModelCapabilities(speculative = false) }
        val runtime = planned(native)
        val spec = LocalEngineSpec(modelPath("mutable.litertlm"), "cpu", 1024)
        runtime.loadEngine(spec)
        assertTrue(runtime.isEngineLoaded(spec))
        java.io.File(spec.modelPath).appendText("replacement content")
        assertFalse(runtime.isEngineLoaded(spec))
    }
}
