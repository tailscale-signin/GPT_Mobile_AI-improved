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
        registry.reset()
        assertEquals("ready", registry.qualify("artifact-runtime-cpu") { "ready" })
    }

    @Test fun `resource pressure does not quarantine an otherwise valid artifact`() = runBlocking {
        val registry = LocalReadinessRegistry()
        runCatching { registry.qualify("model") { error("Not enough memory") } }
        assertEquals("ready", registry.qualify("model") { "ready" })
    }
}
