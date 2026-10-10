package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
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

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    fun validateInstalled(file: File): String {
        val saved = File(file.parentFile, file.name + ".digest")
        val expected = if (saved.isFile && saved.length() <= 256) saved.readLines().firstOrNull().orEmpty() else ""
        return verify(file, expected)
    }

    fun validateInstalledCached(file: File, cacheDirectory: File): String {
        val saved = File(file.parentFile, file.name + ".digest")
        val expected = if (saved.isFile && saved.length() <= 256) saved.readLines().firstOrNull().orEmpty() else ""
        if (expected.isNotBlank() && !expected.matches(Regex("[a-fA-F0-9]{64}"))) {
            throw IOException("Invalid model package checksum metadata")
        }
        val actual = journalIdentity(file, cacheDirectory)
        if (expected.isNotBlank() && !actual.equals(expected, true)) {
            throw IOException("Model package checksum mismatch; native initialization blocked")
        }
        return actual
    }

    /**
     * Returns a stable crash-journal identity without rereading a multi-GB immutable
     * installed model on every QNN load. This cache is not publisher validation.
     */
    fun journalIdentity(file: File, cacheDirectory: File): String {
        val canonical = file.canonicalPath
        val size = file.length()
        val modified = file.lastModified()
        val fileKey = runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()?.toString().orEmpty()
        }.getOrDefault("")
        val key = sha256(canonical.toByteArray())
        val cache = File(cacheDirectory, "model-identity-$key")
        val cached = cache.takeIf { it.isFile && it.length() <= 512 }?.readLines().orEmpty()
        if (cached.size == 5 && cached[0] == canonical && cached[1] == size.toString()
            && cached[2] == modified.toString() && cached[3] == fileKey
            && cached[4].matches(Regex("[a-fA-F0-9]{64}"))
        ) {
            return cached[4]
        }
        val digest = sha256(file)
        val directory = cache.parentFile
        check(directory != null && (directory.isDirectory || directory.mkdirs())) { "Could not prepare model identity cache" }
        val temporary = File(cache.parentFile, "${cache.name}.tmp")
        temporary.writeText(listOf(canonical, size, modified, fileKey, digest).joinToString("\n"))
        if (!temporary.renameTo(cache)) {
            temporary.copyTo(cache, overwrite = true)
            check(temporary.delete()) { "Could not remove temporary model identity cache" }
        }
        return digest
    }
    fun verify(file: File, expected: String): String {
        if (expected.isNotBlank() && !expected.matches(Regex("[a-fA-F0-9]{64}"))) throw IOException("Invalid trusted package checksum metadata")
        val actual = sha256(file)
        if (expected.isNotBlank() && !actual.equals(expected, true)) throw IOException("Model package checksum mismatch; native initialization blocked")
        return actual
    }
}
