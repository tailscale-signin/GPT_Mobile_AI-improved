package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PocketCompiledPreparationFailureTest {
    @Test fun acceleratorGraphFailureRetainsTheExactRequestedBackendStageAndObservedCause() {
        val native = IllegalStateException("native failure text with private path")
        for (backend in listOf(VoiceSpeechBackend.POCKET_GPU, VoiceSpeechBackend.POCKET_NPU)) {
            for (graph in PocketCompiledPreparationGraph.entries) {
                val failure = assertThrows(PocketCompiledPreparationFailure::class.java) {
                    preparePocketCompiledGraph(backend, graph) { throw native }
                }
                assertEquals(backend, failure.backend)
                assertEquals(graph, failure.graph)
                assertSame(native, failure.cause)
                assertEquals("Pocket ${backend.name} preparation failed at ${graph.name}.", failure.message)
            }
        }
    }

    @Test fun missingNativeRuntimeIsTypedWithoutPretendingTheAcceleratorInitialized() {
        val missing = UnsatisfiedLinkError("missing private runtime")
        val failure = assertThrows(PocketCompiledPreparationFailure::class.java) {
            preparePocketCompiledGraph(VoiceSpeechBackend.POCKET_GPU, PocketCompiledPreparationGraph.ENVIRONMENT) { throw missing }
        }
        assertSame(missing, failure.cause)
        assertEquals(PocketCompiledPreparationGraph.ENVIRONMENT, failure.graph)
    }

    @Test fun cancellationMemoryExhaustionAndUnconfirmedCleanupKeepTheirExistingRecoverySemantics() {
        val failures = listOf(
            CancellationException("cancelled"),
            OutOfMemoryError("memory pressure"),
            PocketCompiledCleanupFailure(Any(), IllegalStateException("close failed")),
            PocketCompiledPreparationFailure(VoiceSpeechBackend.POCKET_NPU, PocketCompiledPreparationGraph.LANGUAGE_MODEL, IllegalStateException())
        )
        for (original in failures) {
            val observed = assertThrows(original.javaClass) {
                preparePocketCompiledGraph(VoiceSpeechBackend.POCKET_GPU, PocketCompiledPreparationGraph.ENVIRONMENT) { throw original }
            }
            assertSame(original, observed)
        }
    }
}
