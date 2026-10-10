package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A computed digest identifies bytes; publisher trust requires an independently pinned expected digest. */
internal object PackageDigest {
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var count = input.read(buffer)
        while (count >= 0) {
            if (count > 0) digest.update(buffer, 0, count)
            count = input.read(buffer)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun validateInstalled(file: File): String {
        val saved = File(file.parentFile, file.name + ".digest")
        val expected = if (saved.isFile && saved.length() <= 256) saved.readLines().firstOrNull().orEmpty() else ""
        return verify(file, expected)
    }
    fun verify(file: File, expected: String): String {
        if (expected.isNotBlank() && !expected.matches(Regex("[a-fA-F0-9]{64}"))) throw IOException("Invalid trusted package checksum metadata")
        val actual = sha256(file)
        if (expected.isNotBlank() && !actual.equals(expected, true)) throw IOException("Model package checksum mismatch; native initialization blocked")
        return actual
    }
}
