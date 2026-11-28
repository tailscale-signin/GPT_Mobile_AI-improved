package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionMemoryPolicyTest {
    @Test fun lowMemoryBlocksLoadingEvenWhenTheAvailableCounterLooksLarge() {
        assertFalse(VoiceRecognitionMemoryPolicy.canLoad(Long.MAX_VALUE, true, 1))
        assertFalse(VoiceRecognitionMemoryPolicy.canLoad(null, true, 1))
    }

    @Test fun largeGpuModelsCannotBeAdmittedAgainstOnlyTheirDownloadBytes() {
        val installed = 2_594_373_279L
        val required = VoiceRecognitionMemoryPolicy.estimatedHeadroom(installed, VoiceRecognitionCompute.GPU)
        assertTrue(required > installed * 2)
        assertFalse(VoiceRecognitionMemoryPolicy.canLoad(installed, false, required))
        assertTrue(VoiceRecognitionMemoryPolicy.canLoad(required, false, required))
    }

    @Test fun unknownAvailableMemoryDoesNotBecomeAFabricatedMeasurement() {
        assertTrue(VoiceRecognitionMemoryPolicy.canLoad(null, false, 1024))
        assertFalse(VoiceRecognitionMemoryPolicy.canLoad(0, false, 1024))
        assertThrows(IllegalArgumentException::class.java) { VoiceRecognitionMemoryPolicy.estimatedHeadroom(0, VoiceRecognitionCompute.CPU) }
        assertThrows(IllegalArgumentException::class.java) { VoiceRecognitionMemoryPolicy.estimatedHeadroom(Long.MAX_VALUE, VoiceRecognitionCompute.GPU) }
    }

    @Test fun embeddedNemotronCpuWeightsReserveInitializationCopies() {
        val installed = 682_215_356L
        val required = VoiceRecognitionMemoryPolicy.estimatedHeadroom(installed, VoiceRecognitionCompute.CPU, VoiceRecognitionModel.NEMOTRON_3_5)
        assertTrue(required > 2L * 1024 * 1024 * 1024)
        assertFalse(VoiceRecognitionMemoryPolicy.canLoad(2L * 1024 * 1024 * 1024, false, required))
        val contextBudget = VoiceRecognitionMemoryPolicy.estimatedHeadroom(installed, VoiceRecognitionCompute.NPU, VoiceRecognitionModel.NEMOTRON_3_5)
        assertTrue(required > contextBudget)
    }
}
