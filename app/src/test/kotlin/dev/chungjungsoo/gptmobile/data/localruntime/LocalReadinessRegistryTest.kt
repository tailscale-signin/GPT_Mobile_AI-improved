package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReadinessRegistryTest {
    @Test fun `first incompatibility retains the native cause for diagnosis`() = runBlocking {
        val cause = IllegalStateException("Input tensor not found")
        val failure = runCatching { LocalReadinessRegistry().qualify("model") { throw cause } }.exceptionOrNull()
        assertTrue(failure is LocalArtifactIncompatibleException)
        assertEquals(cause, failure?.cause)
    }

    @Test fun `concurrent incompatible requests perform one native attempt per identity`() = runBlocking {
        val registry = LocalReadinessRegistry()
        var attempts = 0
        val errors = (1..30).map {
            async {
                runCatching {
                    registry.qualify("artifact-runtime-cpu") {
                        attempts++
                        error("Input tensor not found")
                    }
                }.exceptionOrNull()
            }
        }.awaitAll()
        assertEquals(1, attempts)
        assertTrue(errors.all { it is LocalArtifactIncompatibleException })
        assertEquals("ready", registry.qualify("replacement-runtime-cpu") { "ready" })
        registry.reset("artifact-runtime-cpu")
        assertEquals("ready", registry.qualify("artifact-runtime-cpu") { "ready" })
    }

    @Test fun `resource pressure does not quarantine an otherwise valid artifact`() = runBlocking {
        val registry = LocalReadinessRegistry()
        runCatching { registry.qualify("model") { error("Not enough memory") } }
        assertEquals("ready", registry.qualify("model") { "ready" })
    }

    @Test fun `deterministic quarantine survives process restart and reset is scoped to the exact tuple`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("readiness-fixture").toFile()
        try {
            val first = LocalReadinessRegistry(directory)
            for (key in listOf("artifact-a-runtime-gpu", "artifact-b-runtime-npu")) {
                runCatching { first.qualify(key) { error("Input tensor not found") } }
            }
            val restarted = LocalReadinessRegistry(directory)
            var dispatched = false
            assertTrue(runCatching { restarted.qualify("artifact-a-runtime-gpu") { dispatched = true } }.exceptionOrNull() is LocalArtifactIncompatibleException)
            assertTrue(!dispatched)
            restarted.reset("artifact-a-runtime-gpu")
            assertEquals("ready", restarted.qualify("artifact-a-runtime-gpu") { "ready" })
            assertTrue(runCatching { restarted.qualify("artifact-b-runtime-npu") { "unexpected" } }.exceptionOrNull() is LocalArtifactIncompatibleException)
            assertEquals("ready", restarted.qualify("artifact-b-new-runtime-npu") { "ready" })
            assertTrue(directory.listFiles().orEmpty().all { it.name.matches(Regex("[a-f0-9]{64}")) })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `native interruption blocks the route before inspection and survives process restart`() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("native-route-fixture").toFile()
        try {
            LocalReadinessRegistry(directory).rememberNativeInterruption("artifact-runtime-cpu")
            val restarted = LocalReadinessRegistry(directory)
            assertTrue(runCatching { restarted.assertNativeAllowed("artifact-runtime-cpu") }.exceptionOrNull() is LocalNativeQuarantinedException)
            restarted.assertNativeAllowed("artifact-runtime-gpu")
            restarted.assertNativeAllowed("new-artifact-runtime-cpu")
            restarted.reset("artifact-runtime-cpu")
            restarted.assertNativeAllowed("artifact-runtime-cpu")
        } finally {
            directory.deleteRecursively()
        }
    }
}
