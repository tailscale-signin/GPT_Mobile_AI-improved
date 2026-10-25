package dev.chungjungsoo.gptmobile.data.localruntime

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.melo.gguf.GgufNative
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the release-shrunk Java/JNI boundary with real, pinned CPU weights. */
@RunWith(AndroidJUnit4::class)
class GgufNativeInstrumentedTest {
    @Test(timeout = 180_000)
    fun packagedVulkanRuntimeGeneratesOnCompatibleDevice() = assertGpuInference("gpu", dev.chungjungsoo.gptmobile.BuildConfig.GGUF_VULKAN_ENABLED)

    @Test(timeout = 180_000)
    fun packagedOpenClRuntimeGeneratesOnCompatibleDevice() = assertGpuInference("opencl", dev.chungjungsoo.gptmobile.BuildConfig.GGUF_OPENCL_ENABLED)

    private fun assertGpuInference(backend: String, packaged: Boolean) {
        val mandatory = backend in InstrumentationRegistry.getArguments().getString("requiredGgufBackends").orEmpty().split(',')
        if (mandatory) assertTrue("Qualification target requires packaged $backend support", packaged) else assumeTrue("$backend was not packaged", packaged)
        val available = GgufNative.supportsBackend(backend)
        if (mandatory) assertTrue("Compatible qualification target did not enumerate the required $backend GPU/IGPU", available) else assumeTrue("Capability-based skip: this device has no compatible $backend GPU/IGPU", available)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = File(context.cacheDir, "gguf-vulkan-smoke.gguf")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat /data/local/tmp/stories15M-chatml.gguf")).use { input ->
            model.outputStream().use { input.copyTo(it) }
        }
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("c5398d4d93aa7dac7ee95fbc1f8448620fdb5b7445a8b42286c80d587e53dfd5", hash)
            val operation = GgufNative.nextOperationId()
            GgufNative.prepareOperation(0, operation)
            val handle = GgufNative.loadBackendProgress(model.absolutePath.toByteArray(), 512, 2, 8, operation, 2, 128, 64, null, backend)
            assertTrue(GgufNative.executionInfo(handle).contains("backend=$backend"))
            assertTrue(Regex("actualRepeatingLayers=([1-9][0-9]*)").containsMatchIn(GgufNative.executionInfo(handle)))
            val nativeIdentity = kotlinx.serialization.json.Json.parseToJsonElement(GgufNative.engineReceipt(handle)).jsonObject
            assertEquals(if (backend == "gpu") "Vulkan" else "OpenCL", nativeIdentity.getValue("registry").jsonPrimitive.content)
            assertTrue(nativeIdentity.getValue("deviceType").jsonPrimitive.content in setOf("GPU", "IGPU"))
            try {
                repeat(2) {
                    val output = ByteArrayOutputStream()
                    val generation = GgufNative.nextOperationId()
                    GgufNative.prepareOperation(handle, generation)
                    val phases = mutableSetOf<String>()
                    val usage = GgufNative.generateBudgeted(handle, arrayOf("user"), arrayOf("Once upon a time".toByteArray()), 16, 1, 1f, 0f, { bytes ->
                        output.write(bytes)
                        true
                    }, { stage, _, _, _, _, _ -> phases += stage }, 16, generation)
                    assertEquals(19, usage.size)
                    assertEquals(generation, usage[12])
                    assertTrue(usage[17] > 0)
                    assertTrue(phases.containsAll(setOf("NATIVE_ENTRY", "PREFILL_BEGIN", "PREFILL_END", "NATIVE_RETURN")))
                    assertTrue(usage[7] + usage[9] + usage[10] <= usage[11])
                    if (it > 0) assertTrue("Second identical native prompt must observe prefix reuse", usage[5] > 0)
                    assertTrue(usage[0] > 0 && usage[1] in 1L..16L)
                    assertTrue(usage[4] in 0L..1L)
                    assertTrue(output.size() > 0)
                }
                val cancelledOperation = GgufNative.nextOperationId()
                GgufNative.prepareOperation(handle, cancelledOperation)
                val cancelled = GgufNative.generateBudgeted(
                    handle, arrayOf("user"), arrayOf("A new story about the ocean".toByteArray()), 16, 1, 1f, 0f, { false },
                    { stage, _, _, _, _, _ -> if (stage == "PREFILL_BATCH_BEGIN") GgufNative.cancelOperation(handle, cancelledOperation) }, 16, cancelledOperation
                )
                assertEquals(2L, cancelled[4])
                val recovery = GgufNative.nextOperationId()
                GgufNative.prepareOperation(handle, recovery)
                GgufNative.cancelOperation(handle, cancelledOperation)
                val recovered = GgufNative.generateBudgeted(handle, arrayOf("user"), arrayOf("Once upon a time".toByteArray()), 16, 1, 1f, 0f, { true }, null, 16, recovery)
                assertTrue("Cancellation recovery must perform real GPU inference", recovered[1] > 0 && recovered[4] in 0L..1L && recovered[17] > 0)
            } finally {
                GgufNative.unload(handle)
            }
        } finally {
            model.delete()
        }
    }

    @Test
    fun packagedCpuRuntimePreservesRepeatedSessionsAndPartialUsage() {
        assertCpuInference("chatml", "c5398d4d93aa7dac7ee95fbc1f8448620fdb5b7445a8b42286c80d587e53dfd5")
    }

    @Test
    fun packagedCpuRuntimeRendersCustomJinja() {
        GgufNative.validateChatTemplate("{% for m in messages %}{{ m.role|upper }}: {{ m.content }}{% endfor %}".toByteArray())
        assertCpuInference("jinja", "3555a89f9dc6c6f625421a4b88b71b173243d7d960872243aa95ef6a47158ca7")
    }

    private fun assertCpuInference(fixture: String, expectedHash: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = File(context.cacheDir, "gguf-native-smoke-$fixture.gguf")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat /data/local/tmp/stories15M-$fixture.gguf")).use { input ->
            model.outputStream().use { input.copyTo(it) }
        }
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(expectedHash, hash)
            val handle = GgufNative.load(model.absolutePath.toByteArray(), 512, 2, 0)
            try {
                repeat(3) {
                    val output = ByteArrayOutputStream()
                    val usage = GgufNative.generate(handle, arrayOf("user"), arrayOf("Once upon a time".toByteArray()), 16, 1, 1f, 0f) { bytes ->
                        output.write(bytes)
                        true
                    }
                    assertEquals(7, usage.size)
                    assertEquals(usage[0], usage[5] + usage[6])
                    if (it == 0) assertEquals(0L, usage[5]) else assertTrue("Warm request must reuse its unchanged prompt prefix", usage[5] > 0L)
                    assertTrue(usage[0] > 0 && usage[1] in 1L..16L)
                    assertTrue(usage[4] in 0L..1L)
                    assertTrue(output.size() > 0)
                }
                val beforeEntry = GgufNative.nextOperationId()
                GgufNative.prepareOperation(handle, beforeEntry)
                GgufNative.cancelOperation(handle, beforeEntry)
                val preCancelled = GgufNative.generatePrepared(handle, arrayOf("user"), arrayOf("Hello".toByteArray()), 16, 1, 1f, 0f, {
                    fail("Canceled operation emitted bytes")
                    false
                }, null, beforeEntry)
                assertEquals(2L, preCancelled[4])
                assertEquals(0L, preCancelled[0])
                val prefillOperation = GgufNative.nextOperationId()
                GgufNative.prepareOperation(handle, prefillOperation)
                val phases = mutableSetOf<String>()
                val prefillCancelled = GgufNative.generatePrepared(handle, arrayOf("user"), arrayOf("Tell a different story about a bird".toByteArray()), 16, 1, 1f, 0f, {
                    fail("Prefill cancellation emitted bytes")
                    false
                }, { phase, _, _, _, _, _ ->
                    phases += phase
                    if (phase == "PREFILL_BATCH_BEGIN") GgufNative.cancelOperation(handle, prefillOperation)
                }, prefillOperation)
                assertEquals(2L, prefillCancelled[4])
                assertEquals(0L, prefillCancelled[1])
                assertTrue(phases.containsAll(setOf("RENDER_BEGIN", "TOKENIZE_END", "NATIVE_RETURN")))
                assertTrue(GgufNative.buildInfo().contains("operationControl=1"))
                val stopped = GgufNative.generate(handle, arrayOf("user"), arrayOf("Once upon a time".toByteArray()), 64, 1, 1f, 0f) { false }
                assertEquals(2L, stopped[4])
                assertTrue(stopped[0] > 0)
                assertEquals(1L, stopped[1])
                try {
                    GgufNative.generate(handle, arrayOf("user"), arrayOf("x ".repeat(1000).toByteArray()), 64, 1, 1f, 0f) { true }
                    fail("Context overflow was admitted")
                } catch (expected: IllegalStateException) {
                    assertTrue(expected.message.orEmpty().contains("context limit"))
                }
            } finally {
                GgufNative.unload(handle)
            }
            GgufNative.cancel(handle)
            GgufNative.unload(handle)
            try {
                GgufNative.generate(handle, arrayOf("user"), arrayOf(byteArrayOf(65)), 16, 1, 1f, 0f) { true }
                fail("Stale native handle was admitted")
            } catch (_: IllegalStateException) {
                // A repeated or late callback cannot reactivate the destroyed engine.
            }
            val reopened = GgufNative.load(model.absolutePath.toByteArray(), 512, 2, 0)
            GgufNative.unload(reopened)
        } finally {
            model.delete()
        }
    }
}
