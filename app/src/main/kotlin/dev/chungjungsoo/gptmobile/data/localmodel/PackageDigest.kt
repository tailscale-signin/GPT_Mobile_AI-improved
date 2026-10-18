package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** A computed digest identifies bytes; publisher trust requires an independently pinned expected digest. */
internal object PackageDigest {
    fun sha256(file: File, checkCurrent: () -> Unit = {}): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            checkCurrent()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
    fun trustStatus(file: File): String {
        val saved = File(file.parentFile, file.name + ".digest")
        val lines = saved.takeIf { it.isFile && it.length() <= 256 }?.readLines().orEmpty()
        return if (lines.getOrNull(1) == "publisher-digest-verified" && lines.firstOrNull()?.matches(Regex("[a-fA-F0-9]{64}")) == true) "TRUSTED_HASH_PASS" else "TRUSTED_HASH_MISSING"
    }

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
        if (cached.size == 5 &&
            cached[0] == canonical &&
            cached[1] == size.toString() &&
            cached[2] == modified.toString() &&
            cached[3] == fileKey &&
            cached[4].matches(Regex("[a-fA-F0-9]{64}"))
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
    private val assetReceipts = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Kernel change time and inode detect in-place edits as well as atomic replacements.
     * Filesystems without this identity receive full verification, never a timestamp-only fast path.
     */
    fun verifyInstalledAssetCached(file: File, expected: String, checkCurrent: () -> Unit = {}): String {
        checkCurrent()
        fun identity(): String? = runCatching {
            val attributes = Files.readAttributes(file.toPath(), "unix:dev,ino,ctime,size,lastModifiedTime")
            require(attributes.keys.containsAll(setOf("dev", "ino", "ctime", "size", "lastModifiedTime")))
            "${file.canonicalPath}|$expected|${LocalModelInstallationEpoch.current()}|$attributes"
        }.getOrNull()
        val before = identity()
        if (before != null) assetReceipts[before]?.let { return it }
        val digest = verify(file, expected, checkCurrent)
        if (before != null && before == identity()) {
            if (assetReceipts.size >= 256) assetReceipts.clear()
            assetReceipts[before] = digest
        }
        return digest
    }

    fun verify(file: File, expected: String, checkCurrent: () -> Unit = {}): String {
        if (expected.isNotBlank() && !expected.matches(Regex("[a-fA-F0-9]{64}"))) throw IOException("Invalid trusted package checksum metadata")
        val actual = sha256(file, checkCurrent)
        if (expected.isNotBlank() && !actual.equals(expected, true)) throw IOException("Model package checksum mismatch; native initialization blocked")
        return actual
    }
}
