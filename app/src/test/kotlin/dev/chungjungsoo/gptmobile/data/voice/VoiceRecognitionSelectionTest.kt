package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceRecognitionSelectionTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun registrySharesEachPackAcrossThreadChangesAndPreservesTheLegacyTinyOwner() {
        val directory = files.newFolder()
        val tiny = LocalVoiceModelStore(directory)
        val registry = RecognitionModelRegistry(directory, tiny)
        assertEquals(VoiceRecognitionModelCatalog.packs.size, registry.all.size)
        for (pack in VoiceRecognitionModelCatalog.packs) {
            val automatic = registry.store(VoiceRecognitionConfiguration(pack.model, pack.compute, 0))
            val explicit = registry.store(VoiceRecognitionConfiguration(pack.model, pack.compute, 4))
            assertSame(automatic, explicit)
            assertSame(pack, automatic.pack)
            assertFalse(automatic.modelDirectory().exists())
        }
        val selectedTiny = registry.store(VoiceRecognitionConfiguration())
        assertSame(tiny.state, selectedTiny.state)
        assertEquals(tiny.modelDirectory(), selectedTiny.modelDirectory())
        assertEquals(registry.all.size, registry.all.map { it.modelDirectory() }.toSet().size)
    }

    @Test
    fun unavailableRoutesNormalizeToCpuWithoutReplacingTheSelectedModelOrThreadChoice() {
        for (model in VoiceRecognitionModel.entries) {
            for (compute in VoiceRecognitionCompute.entries) {
                val requested = VoiceRecognitionConfiguration(model, compute, 3)
                val actual = requested.supported()
                assertEquals(model, actual.model)
                assertEquals(3, actual.threads)
                if (VoiceRecognitionModelCatalog.pack(model, compute) == null) {
                    assertEquals(VoiceRecognitionCompute.CPU, actual.compute)
                } else {
                    assertSame(requested, actual)
                }
            }
        }
    }

    @Test
    fun invalidThreadCountsAreRejectedBeforeAControllerCanSelectThem() {
        for (threads in listOf(-1, 9, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { VoiceRecognitionConfiguration(threads = threads) }
        }
        assertEquals(0, VoiceRecognitionConfiguration().threads)
        assertEquals(8, VoiceRecognitionConfiguration(threads = 8).threads)
    }
}
