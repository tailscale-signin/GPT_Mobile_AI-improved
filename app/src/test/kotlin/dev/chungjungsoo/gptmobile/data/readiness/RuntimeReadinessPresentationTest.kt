package dev.chungjungsoo.gptmobile.data.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReadinessPresentationTest {
    @Test fun aLoadedGpuConfigurationDoesNotProveGpuExecution() {
        val result = present()
        assertEquals("Loaded · ready for a request", result.status)
        assertTrue(result.configured.orEmpty().contains("Requested GPU"))
        assertEquals("Processor use not reported by this engine", result.observed)
        assertFalse(result.status.contains("measured", ignoreCase = true))
    }

    @Test fun confirmedProcessorCanDifferFromRequestedProcessor() {
        val result = present(observedProcessor = "cpu", processorAcknowledged = true)
        assertTrue(result.configured.orEmpty().contains("Requested GPU"))
        assertEquals("Processor reported: CPU", result.observed)
    }

    @Test fun anUnacknowledgedOrFailedLoadDoesNotConfirmTheProcessor() {
        assertEquals("Processor use not reported by this engine", present(observedProcessor = "vulkan").observed)
        assertEquals("Processor use not reported by this engine", present(observedProcessor = "vulkan", processorAcknowledged = true, loadOutcome = "FAILED").observed)
    }

    @Test fun priorReceiptCannotMakeAnIdleFailedOrRecoveringRuntimeReady() {
        for (lifecycle in listOf("IDLE", "FAILED", "QUARANTINED", "LOADING", "CANCELLING", "RECOVERING", "SHUTDOWN")) {
            val result = present(lifecycle = lifecycle, observedProcessor = "vulkan", processorAcknowledged = true)
            assertFalse(result.status.contains("ready"))
            assertNull(result.observed)
        }
        assertEquals("Runtime needs recovery", present(engineHealth = "RESET_REQUIRED").status)
        assertNull(present(engineHealth = "RESTART_REQUIRED", observedProcessor = "vulkan", processorAcknowledged = true).observed)
    }

    @Test fun activeGenerationIsDistinctFromReadiness() {
        for (phase in listOf("PREFILLING", "DECODING", "WAITING_FOR_TOOL")) {
            assertEquals("Model in use", present(lifecycle = phase).status)
        }
    }

    @Test fun aReleasedModelDoesNotExposeRetainedConfigurationOrReceipt() {
        val result = present(hasLoadedModel = false, lifecycle = "IDLE", engineHealth = "RELEASED", observedProcessor = "vulkan", processorAcknowledged = true)
        assertEquals("No model loaded", result.status)
        assertNull(result.configured)
        assertNull(result.observed)
    }

    @Test fun unknownPlaceholderAndControlTextNeverClaimsAnObservedProcessor() {
        for (processor in listOf("", "unknown", "UNAVAILABLE", "not reported", "gpu\nprivate", "x".repeat(65))) {
            assertEquals("Processor use not reported by this engine", present(observedProcessor = processor, processorAcknowledged = true).observed)
        }
    }

    private fun present(
        hasLoadedModel: Boolean = true,
        lifecycle: String = "READY",
        engineHealth: String = "HEALTHY",
        loadOutcome: String = "READY",
        observedProcessor: String? = null,
        processorAcknowledged: Boolean = false
    ) = runtimeReadinessPresentation(hasLoadedModel, "LiteRT-LM", "gpu", 4096, lifecycle, engineHealth, loadOutcome, observedProcessor, processorAcknowledged)
}
