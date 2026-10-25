package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/** A computed digest identifies bytes; publisher trust requires an independently pinned expected digest. */
internal object PackageDigest {
    internal val bytesHashed = java.util.concurrent.atomic.AtomicLong()
    fun sha256(file: File, checkCurrent: () -> Unit = {}): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            checkCurrent()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) {
                bytesHashed.addAndGet(count.toLong())
                digest.update(buffer, 0, count)
            }
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
    fun journalIdentity(file: File, cacheDirectory: File): String =
        journalIdentityObserved(file, cacheDirectory).digest

    internal data class IdentityObservation(
        val digest: String,
        val strongFileIdentity: String?,
        val cacheDisposition: String,
        val bytesHashed: Long,
        val elapsedMs: Long,
        val lockWaitMs: Long
    )

    private data class IdentityReceipt(val identity: String, val digest: String)
    private val identityReceipts = LinkedHashMap<String, IdentityReceipt>()

    /** Stat/inode/change-time validation is repeated even on memory and persistent cache hits. */
    @Synchronized
    internal fun journalIdentityObserved(
        file: File,
        cacheDirectory: File,
        checkCurrent: () -> Unit = {}
    ): IdentityObservation {
        val started = System.nanoTime()
        checkCurrent()
        val canonical = file.canonicalPath
        val size = file.length()
        val modified = file.lastModified()
        val strongIdentity = installedFileIdentity(file)
        var lockWaitMs = 0L
        fun result(digest: String, disposition: String, bytesHashed: Long) = IdentityObservation(
            digest,
            strongIdentity,
            disposition,
            bytesHashed,
            (System.nanoTime() - started) / 1_000_000,
            lockWaitMs
        )
        fun unchanged() {
            checkCurrent()
            if (strongIdentity != null && strongIdentity != installedFileIdentity(file)) {
                identityReceipts.remove(canonical)
                throw IOException("Model changed during identity validation; retry after installation finishes")
            }
        }
        if (strongIdentity != null) {
            identityReceipts[canonical]?.takeIf { it.identity == strongIdentity }?.let {
                unchanged()
                return result(it.digest, "memory_hit", 0)
            }
        }
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) { "Could not prepare model identity cache" }
        val key = sha256(canonical.toByteArray())
        val cache = File(cacheDirectory, "model-identity-$key")
        fun verifyOrReuse(): IdentityObservation {
            checkCurrent()
            val cached = cache.takeIf { it.isFile && it.length() <= 8192 }?.readLines().orEmpty()
            if (strongIdentity != null &&
                cached.size == 5 &&
                cached[0] == canonical &&
                cached[1] == size.toString() &&
                cached[2] == modified.toString() &&
                cached[3] == strongIdentity &&
                cached[4].matches(Regex("[a-fA-F0-9]{64}"))
            ) {
                unchanged()
                identityReceipts[canonical] = IdentityReceipt(strongIdentity, cached[4])
                return result(cached[4], "disk_hit", 0)
            }
            val digest = sha256(file, checkCurrent)
            unchanged()
            if (strongIdentity != null) {
                if (identityReceipts.size >= 128) identityReceipts.remove(identityReceipts.keys.first())
                identityReceipts[canonical] = IdentityReceipt(strongIdentity, digest)
                val temporary = File.createTempFile(cache.name, ".tmp", cacheDirectory)
                try {
                    temporary.outputStream().use { output ->
                        output.write(listOf(canonical, size, modified, strongIdentity, digest).joinToString("\n").toByteArray())
                        output.fd.sync()
                    }
                    Files.move(temporary.toPath(), cache.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    temporary.delete()
                }
            }
            return result(digest, if (strongIdentity == null) "identity_unavailable_full_hash" else "miss_full_hash", size)
        }
        val waitStarted = System.nanoTime()
        return java.io.RandomAccessFile(File(cacheDirectory, "identity-verification.lock"), "rw").use { lock ->
            lock.channel.lock().use {
                lockWaitMs = (System.nanoTime() - waitStarted) / 1_000_000
                verifyOrReuse()
            }
        }
    }

    internal fun validateInstalledObserved(
        file: File,
        cacheDirectory: File,
        checkCurrent: () -> Unit = {}
    ): IdentityObservation {
        val saved = File(file.parentFile, file.name + ".digest")
        val expected = if (saved.isFile && saved.length() <= 256) saved.readLines().firstOrNull().orEmpty() else ""
        if (expected.isNotBlank() && !expected.matches(Regex("[a-fA-F0-9]{64}"))) {
            throw IOException("Invalid model package checksum metadata")
        }
        return journalIdentityObserved(file, cacheDirectory, checkCurrent).also {
            if (expected.isNotBlank() && !it.digest.equals(expected, true)) {
                throw IOException("Model package checksum mismatch; native initialization blocked")
            }
        }
    }

    @Synchronized
    internal fun clearIdentityMemoryReceipts() = identityReceipts.clear()

    internal fun installedFileIdentity(file: File): String? = runCatching {
        val attributes = Files.readAttributes(file.toPath(), "unix:dev,ino,ctime,size,lastModifiedTime")
        require(attributes.keys.containsAll(setOf("dev", "ino", "ctime", "size", "lastModifiedTime")))
        "${file.canonicalPath}|${attributes.toSortedMap()}"
    }.getOrNull() ?: runCatching {
        // Android's NIO provider may omit the unix view. Os.stat supplies the
        // same inode and nanosecond change/modify identity on supported Android.
        // Keep a leased /proc/self/fd alias readable under its persisted SAF grant.
        val stat = android.system.Os.stat(file.path)
        "${file.canonicalPath}|${stat.st_dev}|${stat.st_ino}|${stat.st_size}|${stat.st_ctim.tv_sec}:${stat.st_ctim.tv_nsec}|${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}"
    }.getOrNull()

    private val assetReceipts = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Kernel change time and inode detect in-place edits as well as atomic replacements.
     * Filesystems without this identity receive full verification, never a timestamp-only fast path.
     */
    @Volatile private var receiptDirectory: File? = null
    fun configureReceiptDirectory(directory: File?) {
        receiptDirectory = directory
    }

    internal fun clearMemoryReceipts() {
        assetReceipts.clear()
    }

    @Synchronized
    fun verifyInstalledAssetCached(file: File, expected: String, checkCurrent: () -> Unit = {}): String {
        checkCurrent()
        require(expected.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid trusted package checksum metadata" }
        val started = System.nanoTime()
        val before = installedFileIdentity(file)?.let { "$it|${expected.lowercase()}" }
        fun report(reason: String) {
            val duration = (System.nanoTime() - started) / 1_000_000
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("LocalModel", "MODEL_CHECKSUM · durationMs=$duration · cache=$reason · bytes=${file.length()}")
            dev.chungjungsoo.gptmobile.data.diagnostics.LocalRuntimeIncidentHistory.record("CHECKSUM_FINISHED", mapOf("durationMs" to duration.toString(), "cache" to reason, "bytes" to file.length().toString()))
        }
        if (before != null) {
            assetReceipts[before]?.let {
                report("memory_hit")
                return it
            }
        }
        val receipt = before?.let { key -> receiptDirectory?.let { File(it, sha256(key.toByteArray()) + ".verified") } }
        fun verifyOrReuse(): String {
            checkCurrent()
            if (before != null && receipt?.isFile == true && receipt.length() <= 8192 && receipt.readText() == before + "\n" + expected.lowercase() && before == installedFileIdentity(file)?.let { "$it|${expected.lowercase()}" }) {
                assetReceipts[before] = expected.lowercase()
                report("disk_hit")
                return expected.lowercase()
            }
            val digest = verify(file, expected, checkCurrent)
            if (before != null) {
                check(before == installedFileIdentity(file)?.let { "$it|${expected.lowercase()}" }) { "Model changed during checksum verification" }
                if (assetReceipts.size >= 256) assetReceipts.clear()
                assetReceipts[before] = digest
                receipt?.let {
                    val temporary = File(it.parentFile, it.name + ".tmp")
                    temporary.outputStream().use { output ->
                        output.write((before + "\n" + digest).toByteArray())
                        output.fd.sync()
                    }
                    Files.move(temporary.toPath(), it.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                }
            }
            report(if (before == null) "identity_unavailable_full_hash" else "miss_full_hash")
            return digest
        }
        // Cross-process inspection and engine loading share one verification receipt.
        return if (receipt == null) {
            verifyOrReuse()
        } else {
            check(receipt.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Could not prepare checksum receipt directory" }
            java.io.RandomAccessFile(File(receipt.parentFile, "verification.lock"), "rw").use { lock ->
                lock.channel.lock().use { verifyOrReuse() }
            }
        }
    }

    fun verify(file: File, expected: String, checkCurrent: () -> Unit = {}): String {
        if (expected.isNotBlank() && !expected.matches(Regex("[a-fA-F0-9]{64}"))) throw IOException("Invalid trusted package checksum metadata")
        val actual = sha256(file, checkCurrent)
        if (expected.isNotBlank() && !actual.equals(expected, true)) throw IOException("Model package checksum mismatch; native initialization blocked")
        return actual
    }
}
