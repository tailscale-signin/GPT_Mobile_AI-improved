package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTransactionalImportTest {
    private fun bytes(suffix: String) = ("LITERTLM" + suffix.repeat(50)).toByteArray()

    @Test fun contentIdentityReusesIdenticalBytesAndNeverOverwritesFilenameCollision() {
        val root = Files.createTempDirectory("model-import").toFile()
        try {
            fun install(name: String, body: ByteArray) = LocalModelLocator.importModel(body.inputStream(), name, root, minSizeBytes = 8) as LocalModelImportResult.Success
            val first = install("same.litertlm", bytes("first"))
            val different = install("same.litertlm", bytes("second"))
            val renamed = install("renamed.litertlm", bytes("first"))
            assertNotEquals(first.record.catalogEntryId, different.record.catalogEntryId)
            assertEquals(first.record, renamed.record)
            assertEquals("same.litertlm", first.record.fileName)
            assertEquals(first.absoluteFilePath, renamed.absoluteFilePath)
            assertTrue(File(first.absoluteFilePath).readBytes().contentEquals(bytes("first")))
            assertTrue(File(different.absoluteFilePath).readBytes().contentEquals(bytes("second")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun cancellationLeavesNoUsablePartialOrStagingDirectory() {
        val root = Files.createTempDirectory("cancel-import").toFile()
        try {
            val failure = runCatching { LocalModelLocator.importModel(bytes("input").inputStream(), "input.litertlm", root, minSizeBytes = 8, checkCurrent = { throw CancellationException("user stopped") }) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(File(root, "models").listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun malformedHeaderAndUnsupportedFormatsNeverRegister() {
        val root = Files.createTempDirectory("invalid-import").toFile()
        try {
            val malformed = LocalModelLocator.importModel(ByteArray(100).inputStream(), "bad.litertlm", root, minSizeBytes = 8)
            assertTrue(malformed is LocalModelImportResult.Failure)
            assertFalse(LocalModelLocator.isSupportedModelFile("renamed.task"))
            assertFalse(LocalModelLocator.isSupportedModelFile("weights.tflite"))
            assertTrue(File(root, "models").listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
