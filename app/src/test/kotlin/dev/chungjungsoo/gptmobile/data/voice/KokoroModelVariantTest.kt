package dev.chungjungsoo.gptmobile.data.voice

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KokoroModelVariantTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun defaultInstallationKeepsTheExistingCompactModelIdentity() {
        val directory = files.newFolder()
        val default = KokoroModelPack.store(directory)
        val explicit = KokoroModelPack.store(directory, variant = KokoroModelVariant.INT8)
        assertEquals(default.modelDirectory(), explicit.modelDirectory())
        assertEquals(KokoroModelPack.MODEL, KokoroModelVariant.INT8.modelFile)
        assertEquals(KokoroModelPack.MODEL_SHA256, KokoroModelVariant.INT8.modelSha256)
        assertEquals(KokoroModelPack.DOWNLOAD_BYTES, default.state.value.totalBytes)
    }

    @Test fun variantsHaveIndependentStorageProgressAndWeightIdentity() = runBlocking {
        val directory = files.newFolder()
        val compact = KokoroModelPack.store(directory)
        val original = KokoroModelPack.store(directory, variant = KokoroModelVariant.ORIGINAL_FP32)
        assertNotEquals(compact.modelDirectory(), original.modelDirectory())
        assertNotEquals(KokoroModelVariant.INT8.modelSha256, KokoroModelVariant.ORIGINAL_FP32.modelSha256)
        assertEquals(349_886_476L, original.state.value.totalBytes)
        compact.refresh()
        assertEquals(LocalVoiceModelStatus.NOT_INSTALLED, compact.state.value.status)
        assertEquals(LocalVoiceModelStatus.CHECKING, original.state.value.status)
        assertFalse(original.verifyInstalledFiles())
    }

    @Test fun removingOneVariantPreservesTheOtherInstalledDirectory() = runBlocking {
        val directory = files.newFolder()
        val compact = KokoroModelPack.store(directory)
        val original = KokoroModelPack.store(directory, variant = KokoroModelVariant.ORIGINAL_FP32)
        compact.modelDirectory().mkdirs()
        original.modelDirectory().mkdirs()
        val other = File(original.modelDirectory(), "retained-test-fixture").apply { writeText("original variant") }
        compact.remove()
        assertFalse(compact.modelDirectory().exists())
        assertTrue(other.isFile)
    }
}
