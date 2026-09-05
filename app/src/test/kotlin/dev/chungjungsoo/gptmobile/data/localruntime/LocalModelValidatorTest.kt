package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class LocalModelValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun nonExistentFile_returnsFailure() {
        val nonExistent = File(tempFolder.root, "does_not_exist.bin")
        val result = LocalModelValidator.validate(nonExistent.absolutePath)
        assertTrue(result is ModelValidationResult.Invalid)
        val invalid = result as ModelValidationResult.Invalid
        assertEquals(ModelValidationResult.Invalid.Reason.NOT_FOUND, invalid.reason)
        assertTrue(invalid.details?.contains("does not exist") == true)
    }

    @Test
    fun directory_returnsFailure() {
        val dir = tempFolder.newFolder("model_dir")
        val result = LocalModelValidator.validate(dir.absolutePath)
        assertTrue(result is ModelValidationResult.Invalid)
        val invalid = result as ModelValidationResult.Invalid
        assertEquals(ModelValidationResult.Invalid.Reason.NOT_A_FILE, invalid.reason)
        assertTrue(invalid.details?.contains("regular file") == true)
    }

    @Test
    fun undersizedFile_returnsFailure() {
        val tinyFile = tempFolder.newFile("tiny.bin")
        tinyFile.writeBytes(ByteArray(100))
        val result = LocalModelValidator.validate(tinyFile.absolutePath)
        assertTrue(result is ModelValidationResult.Invalid)
        val invalid = result as ModelValidationResult.Invalid
        assertEquals(ModelValidationResult.Invalid.Reason.FILE_TOO_SMALL, invalid.reason)
        assertTrue(invalid.details?.contains("below minimum required") == true)
    }

    @Test
    fun validTfliteFile_passesValidation() {
        val modelFile = tempFolder.newFile("model.tflite")
        FileOutputStream(modelFile).use { fos ->
            val header = ByteArray(8)
            header[4] = 'T'.code.toByte()
            header[5] = 'F'.code.toByte()
            header[6] = 'L'.code.toByte()
            header[7] = '3'.code.toByte()
            fos.write(header)
            // Write padding to exceed minimum 10MB
            val chunk = ByteArray(1024 * 1024)
            repeat(10) {
                fos.write(chunk)
            }
        }

        val result = LocalModelValidator.validate(
            modelPath = modelFile.absolutePath,
            verifyTFLiteHeader = true
        )
        assertTrue(result is ModelValidationResult.Valid)
        val valid = result as ModelValidationResult.Valid
        assertEquals(modelFile.absolutePath, valid.file.absolutePath)
        assertTrue(valid.sizeBytes >= 10L * 1024L * 1024L)
    }

    @Test
    fun checksumVerification_matchesCalculatedSha256() {
        val modelFile = tempFolder.newFile("checksum_test.bin")
        val content = ByteArray(1024 * 1024 * 11) { (it % 256).toByte() }
        modelFile.writeBytes(content)

        val digest = MessageDigest.getInstance("SHA-256")
        val expectedSha = digest.digest(content).joinToString("") { "%02x".format(it) }

        val validResult = LocalModelValidator.validate(
            modelPath = modelFile.absolutePath,
            expectedSha256 = expectedSha,
            verifyTFLiteHeader = false
        )
        assertTrue(validResult is ModelValidationResult.Valid)
        val valid = validResult as ModelValidationResult.Valid
        assertEquals(expectedSha, valid.sha256Hex)

        val invalidResult = LocalModelValidator.validate(
            modelPath = modelFile.absolutePath,
            expectedSha256 = "deadbeef00000000deadbeef00000000deadbeef00000000deadbeef00000000",
            verifyTFLiteHeader = false
        )
        assertTrue(invalidResult is ModelValidationResult.Invalid)
        val invalid = invalidResult as ModelValidationResult.Invalid
        assertEquals(ModelValidationResult.Invalid.Reason.CHECKSUM_MISMATCH, invalid.reason)
        assertTrue(invalid.details?.contains("SHA-256") == true)
    }
}
