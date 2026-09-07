package dev.chungjungsoo.gptmobile.data.localmodel

import dev.chungjungsoo.gptmobile.data.localruntime.LocalModelValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class LocalModelLocatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun supportedModelFileExtensions() {
        assertTrue(LocalModelLocator.isSupportedModelFile("gemma-2b.bin"))
        assertTrue(LocalModelLocator.isSupportedModelFile("model.task"))
        assertTrue(LocalModelLocator.isSupportedModelFile("model.tflite"))
        assertTrue(LocalModelLocator.isSupportedModelFile("model.litertmodel"))
        assertTrue(LocalModelLocator.isSupportedModelFile("model.gguf"))

        assertFalse(LocalModelLocator.isSupportedModelFile("model.txt"))
        assertFalse(LocalModelLocator.isSupportedModelFile("model.exe"))
        assertFalse(LocalModelLocator.isSupportedModelFile("model.json"))
    }

    @Test
    fun generateCatalogEntryIdProducesSafeId() {
        val entryId = LocalModelLocator.generateCatalogEntryId("Gemma-2b-it.bin")
        assertEquals("local_model_gemma-2b-it.bin", entryId)
        assertTrue(LocalModelDownloadPaths.isValidPathSegment(entryId))
    }

    @Test
    fun locateExistingModelSuccess() {
        val file = tempFolder.newFile("test_local_model.bin")
        // Write enough dummy bytes to satisfy min size
        val dummyData = ByteArray(1024)
        file.writeBytes(dummyData)

        val result = LocalModelLocator.locateExistingModel(file, minSizeBytes = 512L)
        assertTrue(result is LocalModelImportResult.Success)
        val success = result as LocalModelImportResult.Success
        assertEquals(file.name, success.record.fileName)
        assertEquals(LocalModelStatus.READY, success.record.status)
        assertEquals(LocalModelLocator.LOCAL_COMMIT_HASH, success.record.commitHash)
    }

    @Test
    fun locateExistingModelRejectsInvalidExtension() {
        val file = tempFolder.newFile("readme.txt")
        file.writeBytes(ByteArray(1024))

        val result = LocalModelLocator.locateExistingModel(file, minSizeBytes = 512L)
        assertTrue(result is LocalModelImportResult.Failure)
        val failure = result as LocalModelImportResult.Failure
        assertEquals(LocalModelImportResult.Failure.Reason.UNSUPPORTED_FORMAT, failure.reason)
    }

    @Test
    fun importModelFromStreamSuccess() {
        val targetRootDir = tempFolder.newFolder("target_models")
        val content = ByteArray(2048) { it.toByte() }
        val stream = ByteArrayInputStream(content)

        val result = LocalModelLocator.importModel(
            inputStream = stream,
            fileName = "custom_model.tflite",
            targetModelsRootDir = targetRootDir,
            minSizeBytes = 1024L
        )

        assertTrue(result is LocalModelImportResult.Success)
        val success = result as LocalModelImportResult.Success
        val targetFile = File(success.absoluteFilePath)
        assertTrue(targetFile.exists())
        assertEquals(2048L, targetFile.length())
        assertEquals(LocalModelStatus.READY, success.record.status)
    }

    @Test
    fun importModelRejectsTooSmallFile() {
        val targetRootDir = tempFolder.newFolder("target_models_small")
        val content = ByteArray(100)
        val stream = ByteArrayInputStream(content)

        val result = LocalModelLocator.importModel(
            inputStream = stream,
            fileName = "small_model.bin",
            targetModelsRootDir = targetRootDir,
            minSizeBytes = 1024L
        )

        assertTrue(result is LocalModelImportResult.Failure)
        val failure = result as LocalModelImportResult.Failure
        assertEquals(LocalModelImportResult.Failure.Reason.INVALID_MODEL, failure.reason)
    }
}
