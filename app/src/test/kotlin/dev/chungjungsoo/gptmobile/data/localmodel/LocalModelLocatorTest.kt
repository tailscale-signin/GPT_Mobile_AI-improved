package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelLocatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun supportedModelFileExtensions() {
        assertFalse(LocalModelLocator.isSupportedModelFile("gemma-2b.bin"))
        assertFalse(LocalModelLocator.isSupportedModelFile("model.task"))
        assertFalse(LocalModelLocator.isSupportedModelFile("model.tflite"))
        assertFalse(LocalModelLocator.isSupportedModelFile("model.litertmodel"))
        assertTrue(LocalModelLocator.isSupportedModelFile("model.litertlm"))
        assertFalse(LocalModelLocator.isSupportedModelFile("model.gguf"))

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
        val file = tempFolder.newFile("test_local_model.litertlm")
        // Write enough dummy bytes to satisfy min size
        val dummyData = ByteArray(1024).also { "LITERTLM".toByteArray().copyInto(it) }
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
        val content = ByteArray(2048).also { "LITERTLM".toByteArray().copyInto(it) }
        val stream = ByteArrayInputStream(content)

        val result = LocalModelLocator.importModel(
            inputStream = stream,
            fileName = "custom_model.litertlm",
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
            fileName = "small_model.litertlm",
            targetModelsRootDir = targetRootDir,
            minSizeBytes = 1024L
        )

        assertTrue(result is LocalModelImportResult.Failure)
        val failure = result as LocalModelImportResult.Failure
        assertEquals(LocalModelImportResult.Failure.Reason.INVALID_MODEL, failure.reason)
    }

    @Test
    fun failedReimportPreservesTheWorkingFile() {
        val root = tempFolder.newFolder("atomic")
        val valid = ByteArray(1024).also { "LITERTLM".toByteArray().copyInto(it) }
        val original = LocalModelLocator.importModel(valid.inputStream(), "model.litertlm", root, 512) as LocalModelImportResult.Success
        val failed = LocalModelLocator.importModel(ByteArray(100).inputStream(), "model.litertlm", root, 512)
        assertTrue(failed is LocalModelImportResult.Failure)
        assertTrue(File(original.absoluteFilePath).readBytes().contentEquals(valid))
    }

    @Test
    fun importRejectsPathTraversalBeforeWriting() {
        val root = tempFolder.newFolder("paths")
        val result = LocalModelLocator.importModel(ByteArray(1024).inputStream(), "../escape.litertlm", root, 512)
        assertTrue(result is LocalModelImportResult.Failure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }
}
