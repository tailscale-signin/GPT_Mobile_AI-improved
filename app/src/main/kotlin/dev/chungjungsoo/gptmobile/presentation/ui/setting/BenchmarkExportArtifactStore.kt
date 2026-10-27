package dev.chungjungsoo.gptmobile.presentation.ui.setting

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class BenchmarkExportArtifact(
    val id: String,
    val detailed: Boolean,
    val createdAt: Long,
    val bytes: Long,
    val sha256: String
) {
    val fileName: String get() = "${if (detailed) "benchmark-debug" else "benchmarks"}-$createdAt-${id.take(8)}.json"
}

internal class BenchmarkExportIntegrityFailure(message: String) : IOException(message)

/** Complete private files survive a failed document provider or a later process restart. */
internal class BenchmarkExportArtifactStore(
    private val directory: () -> File,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun prepare(detailed: Boolean, write: suspend (OutputStream) -> Unit): BenchmarkExportArtifact = withContext(Dispatchers.IO) {
        val folder = directory()
        check(folder.isDirectory || folder.mkdirs()) { "Unable to create the private export folder." }
        val id = UUID.randomUUID().toString()
        val partial = File(folder, "$id.partial")
        val content = File(folder, "$id.json")
        val receiptPartial = File(folder, "$id.receipt.partial")
        val receipt = File(folder, "$id.receipt")
        val digest = MessageDigest.getInstance("SHA-256")
        val preparationContext = currentCoroutineContext()
        var bytes = 0L
        val lease = File(folder, "$id.lock")
        try {
            FileOutputStream(lease, true).channel.use { channel ->
                channel.lock().use {
                    var committed = false
                    try {
                        FileOutputStream(partial).use { file ->
                            val counted = object : OutputStream() {
                                override fun write(value: Int) {
                                    preparationContext.ensureActive()
                                    file.write(value)
                                    digest.update(value.toByte())
                                    bytes++
                                }
                                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                    preparationContext.ensureActive()
                                    file.write(buffer, offset, length)
                                    digest.update(buffer, offset, length)
                                    bytes += length
                                }
                                override fun flush() = file.flush()
                            }
                            BufferedOutputStream(counted, COPY_BUFFER_BYTES).use { buffered ->
                                write(buffered)
                                currentCoroutineContext().ensureActive()
                                buffered.flush()
                                file.fd.sync()
                            }
                        }
                        check(bytes > 0) { "The benchmark export was empty." }
                        Files.move(partial.toPath(), content.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        val artifact = BenchmarkExportArtifact(id, detailed, now(), bytes, digest.digest().hex())
                        val properties = Properties().apply {
                            setProperty("id", artifact.id)
                            setProperty("detailed", artifact.detailed.toString())
                            setProperty("createdAt", artifact.createdAt.toString())
                            setProperty("bytes", artifact.bytes.toString())
                            setProperty("sha256", artifact.sha256)
                        }
                        FileOutputStream(receiptPartial).use { output ->
                            properties.store(output, "Complete benchmark export")
                            output.fd.sync()
                        }
                        Files.move(receiptPartial.toPath(), receipt.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        committed = true
                        artifact
                    } finally {
                        partial.delete()
                        receiptPartial.delete()
                        if (!committed) content.delete()
                    }
                }
            }
        } finally {
            lease.delete()
        }
    }

    /** Receipt files are small; the export body is never hydrated to restore the retry action. */
    suspend fun latest(): BenchmarkExportArtifact? = withContext(Dispatchers.IO) {
        cleanupStaleOrphans()
        val folder = directory()
        folder.listFiles { file -> file.name.endsWith(".receipt") }.orEmpty()
            .mapNotNull { readReceipt(folder, it) }.maxByOrNull { it.createdAt }
    }

    suspend fun find(id: String): BenchmarkExportArtifact? = withContext(Dispatchers.IO) {
        require(validId(id))
        val folder = directory()
        readReceipt(folder, File(folder, "$id.receipt"))
    }

    /** Process death can leave staging files; only aged, unleased owned files are eligible. */
    suspend fun cleanupStaleOrphans(): Int = withContext(Dispatchers.IO) {
        val folder = directory()
        val cutoff = now() - STALE_ORPHAN_AGE_MS
        val groups = folder.listFiles().orEmpty().mapNotNull { file ->
            val id = OWNED_FILE_NAME.matchEntire(file.name)?.groupValues?.get(1)
            if (id == null || !validId(id) || !file.isFile) null else id to file
        }.groupBy({ it.first }, { it.second })
        var removed = 0
        for ((id, files) in groups) {
            currentCoroutineContext().ensureActive()
            if (files.any { it.lastModified() <= 0 || it.lastModified() > cutoff }) continue
            val lease = File(folder, "$id.lock")
            val existingLease = lease.exists()
            var held = false
            try {
                FileOutputStream(lease, true).channel.use { channel ->
                    val lock = try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (lock != null) {
                        lock.use owned@{
                            held = true
                            // Recheck after acquiring the lease: a writer may have finished
                            // between the directory scan and this nonblocking lock attempt.
                            if (files.any { it.exists() && (it.lastModified() <= 0 || it.lastModified() > cutoff) }) return@owned
                            // Even an unreadable published receipt may represent a complete
                            // export. Cleanup never deletes its receipt or payload.
                            val published = File(folder, "$id.receipt").exists()
                            files.filter { file ->
                                file != lease && (!published || file.name.endsWith(".partial"))
                            }.forEach { file -> if (file.delete()) removed++ }
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A denied/unsupported lease is not permission to remove a file.
            } finally {
                if (held && lease.delete() && existingLease) removed++
            }
        }
        removed
    }

    private fun readReceipt(folder: File, receipt: File): BenchmarkExportArtifact? = runCatching {
        require(receipt.length() in 1..MAX_RECEIPT_BYTES)
        val properties = Properties().apply { receipt.inputStream().use { load(it) } }
        val id = requireNotNull(properties.getProperty("id"))
        require(validId(id) && receipt.name == "$id.receipt")
        val artifact = BenchmarkExportArtifact(
            id,
            requireNotNull(properties.getProperty("detailed").toBooleanStrictOrNull()),
            properties.getProperty("createdAt").toLong(),
            properties.getProperty("bytes").toLong(),
            requireNotNull(properties.getProperty("sha256"))
        )
        require(artifact.createdAt > 0 && artifact.bytes > 0 && artifact.sha256.matches(Regex("[0-9a-f]{64}")))
        require(File(folder, "$id.json").length() == artifact.bytes)
        artifact
    }.getOrNull()

    /** Validate before opening/truncating a destination, then retire the receipt only after close. */
    suspend fun saveTo(artifact: BenchmarkExportArtifact, openDestination: () -> OutputStream?): Unit = withContext(Dispatchers.IO) {
        verify(artifact)
        checkNotNull(openDestination()) { "Unable to open the export destination." }.use { destination -> copyTo(artifact, destination) }
        complete(artifact)
    }

    suspend fun verify(artifact: BenchmarkExportArtifact) {
        copyTo(
            artifact,
            object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
            }
        )
    }

    /** The caller owns destination close/flush errors and reports success only after close. */
    suspend fun copyTo(artifact: BenchmarkExportArtifact, destination: OutputStream): Unit = withContext(Dispatchers.IO) {
        require(validId(artifact.id))
        val source = File(directory(), "${artifact.id}.json")
        if (source.length() != artifact.bytes) throw BenchmarkExportIntegrityFailure("The retained export is incomplete.")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        source.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                destination.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                bytes += count
            }
        }
        if (bytes != artifact.bytes || digest.digest().hex() != artifact.sha256) throw BenchmarkExportIntegrityFailure("The retained export failed its integrity check.")
        destination.flush()
    }

    suspend fun complete(artifact: BenchmarkExportArtifact): Unit = withContext(Dispatchers.IO) {
        require(validId(artifact.id))
        val folder = directory()
        File(folder, "${artifact.id}.receipt").delete()
        File(folder, "${artifact.id}.json").delete()
    }

    private fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_RECEIPT_BYTES = 8 * 1024L
        private const val STALE_ORPHAN_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        private val OWNED_FILE_NAME = Regex("""^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.(json|partial|receipt|receipt\.partial|lock)$""")
    }
}
