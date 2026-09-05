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
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("does not exist") == true)
    }

    @Test
    fun directory_returnsFailure() {
        val dir = tempFolder.newFolder("model_dir")
        val result = LocalModelValidator.validate(dir.absolutePath)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("directory") == true)
    }

    @Test
    fun undersizedFile_returnsFailure() {
        val tinyFile = tempFolder.newFile("tiny.bin")
        tinyFile.writeBytes(ByteArray(100))
        val result = LocalModelValidator.validate(tinyFile.absolutePath)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("suspiciously small") == true)
    }

    @Test
    fun validTfliteFile_passesValidation() {
        val modelFile = tempFolder.newFile("model.task")
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

        val result = LocalModelValidator.validate(modelFile.absolutePath)
        assertTrue(result.isValid)
        assertEquals(null, result.errorMessage)
    }

    @Test
    fun checksumVerification_matchesCalculatedSha256() {
        val modelFile = tempFolder.newFile("checksum_test.bin")
        val content = ByteArray(1024 * 1024 * 11) { (it % 256).toByte() }
        modelFile.writeBytes(content)

        val digest = MessageDigest.getInstance("SHA-256")
        val expectedSha = digest.digest(content).joinToString("") { "%02x".format(it) }

        val validResult = LocalModelValidator.validate(
            path = modelFile.absolutePath,
            expectedSha256 = expectedSha,
            requireMagicHeader = false
        )
        assertTrue(validResult.isValid)

        val invalidResult = LocalModelValidator.validate(
            path = modelFile.absolutePath,
            expectedSha256 = "deadbeef00000000deadbeef00000000deadbeef00000000deadbeef00000000",
            requireMagicHeader = false
        )
        assertFalse(invalidResult.isValid)
        assertTrue(invalidResult.errorMessage?.contains("Checksum mismatch") == true)
    }
}
