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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the release-shrunk Java/JNI boundary with real, pinned CPU weights. */
@RunWith(AndroidJUnit4::class)
class GgufNativeInstrumentedTest {
    @Test
    fun packagedVulkanRuntimeGeneratesOnCompatibleDevice() {
        assumeTrue(dev.chungjungsoo.gptmobile.BuildConfig.GGUF_VULKAN_ENABLED)
        assumeTrue("Device has no compatible Vulkan GPU", GgufNative.supportsGpuOffload())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = File(context.cacheDir, "gguf-vulkan-smoke.gguf")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("cat /data/local/tmp/stories15M-chatml.gguf")).use { input ->
            model.outputStream().use { input.copyTo(it) }
        }
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("c5398d4d93aa7dac7ee95fbc1f8448620fdb5b7445a8b42286c80d587e53dfd5", hash)
            val handle = GgufNative.load(model.absolutePath.toByteArray(), 512, 2, 999)
            try {
                repeat(2) {
                    val output = ByteArrayOutputStream()
                    val usage = GgufNative.generate(handle, arrayOf("user"), arrayOf("Once upon a time".toByteArray()), 16, 1, 1f, 0f) { bytes ->
                        output.write(bytes)
                        true
                    }
                    assertTrue(usage[0] > 0 && usage[1] in 1L..16L)
                    assertTrue(usage[4] in 0L..1L)
                    assertTrue(output.size() > 0)
                }
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
