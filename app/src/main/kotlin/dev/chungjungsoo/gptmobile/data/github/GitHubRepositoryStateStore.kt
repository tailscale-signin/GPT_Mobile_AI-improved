package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class GitHubTaskSnapshot(
    val accountId: String,
    val connectionUid: String,
    val conversationId: String,
    val taskId: String,
    val owner: String,
    val repo: String,
    val ref: String,
    val commitSha: String,
    val treeSha: String,
    val evidenceHandles: List<String> = emptyList(),
    val stagedPatchId: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Encrypted cache bodies and separate hashed publication fences; temporary sessions stay in memory. */
@Singleton
class GitHubRepositoryStateStore private constructor(private val directoryProvider: () -> File?, private val vault: SecretVault?, private val publicationClaimLimit: Int = MAX_PUBLICATION_CLAIMS, private val protectedCapacityBytes: Long = MAX_PROTECTED_BYTES) {
    @Inject constructor(@ApplicationContext context: Context, vault: SecretVault) : this({ File(context.noBackupFilesDir, "github-repository-state") }, vault)
    constructor(directory: File? = null, publicationClaimLimit: Int = MAX_PUBLICATION_CLAIMS, protectedCapacityBytes: Long = MAX_PROTECTED_BYTES) : this({ directory }, null, publicationClaimLimit, protectedCapacityBytes)
    init {
        require(publicationClaimLimit > 0)
        require(protectedCapacityBytes in MAX_PROTECTED_RECORD_BYTES.toLong()..MAX_PROTECTED_BYTES)
    }
    private val mutex = Mutex()
    private val directory: File? by lazy(directoryProvider)
    private val values = LinkedHashMap<String, JsonObject>(16, 0.75f, true)
    private var memoryCharacters = 0
    private val protectedValues = linkedMapOf<String, JsonObject>()
    private val reservations = mutableSetOf<String>()
    private var legacyReceiptsMigrated = false

    // Publication fences are authority, not cache. A pruned receipt must never
    // turn the same execution into a new write. Markers contain only hashed IDs.
    private val publicationClaims = LinkedHashSet<String>()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Existence only: index coverage must not decode every retained source body. */
    suspend fun contains(scope: String, key: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val identity = "$scope:$key"
            protectedValues.containsKey(identity) || protectedFile(identity)?.isFile == true || values.containsKey(identity) || directory?.let { File(it, digest(identity) + ".record") }?.let { it.isFile && it.length() in 1..MAX_RECORD_BYTES.toLong() } == true || claimCheckpoint(identity, key) != null
        }
    }

    /** A memory hit does not prove that a cache body survived persistent-store pruning. */
    internal suspend fun containsRetainedBody(scope: String, key: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val identity = "$scope:$key"
            val dir = directory
            if (dir == null) {
                values.containsKey(identity)
            } else {
                File(dir, digest(identity) + ".record").let { it.isFile && it.length() in 1..MAX_RECORD_BYTES.toLong() }
            }
        }
    }

    suspend fun read(scope: String, key: String): JsonObject? = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked(scope, key) }
    }

    suspend fun write(scope: String, key: String, value: JsonObject) = withContext(Dispatchers.IO) {
        mutex.withLock { writeLocked(scope, key, value) }
    }

    /** Update metadata without losing another session's selected revision or evidence handles. */
    suspend fun update(scope: String, key: String, transform: (JsonObject?) -> JsonObject): JsonObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            transform(readLocked(scope, key)).also { writeLocked(scope, key, it) }
        }
    }

    private suspend fun readLocked(scope: String, key: String): JsonObject? {
        migrateLegacyReceipts()
        val identity = "$scope:$key"
        protectedValues[identity]?.let { return it }
        val protected = protectedFile(identity)?.takeIf { it.isFile }
        if (protected != null) {
            if (protected.length() !in 1..MAX_PROTECTED_RECORD_BYTES.toLong()) return claimCheckpoint(identity, key)
            return runCatching { json.parseToJsonElement(crypt(identity, protected.readBytes(), false).decodeToString()) as? JsonObject }.getOrNull() ?: claimCheckpoint(identity, key)
        }
        values[identity]?.let { return it }
        val file = directory?.let { File(it, digest(identity) + ".record") } ?: return claimCheckpoint(identity, key)
        if (!file.isFile || file.length() > MAX_RECORD_BYTES) return claimCheckpoint(identity, key)
        val raw = file.readBytes()
        val value = runCatching { json.parseToJsonElement(crypt(identity, raw, false).decodeToString()) as? JsonObject }.getOrNull() ?: return claimCheckpoint(identity, key)
        remember(identity, value)
        file.setLastModified(System.currentTimeMillis())
        return value
    }

    private suspend fun writeLocked(scope: String, key: String, value: JsonObject) {
        migrateLegacyReceipts()
        val identity = "$scope:$key"
        val plain = value.toString().encodeToByteArray()
        require(plain.size <= MAX_RECORD_BYTES - 28) { "GitHub state record exceeds its byte budget." }
        if (CLAIM_KEY.matches(key)) {
            writeProtectedLocked(identity, key, value, plain)
            return
        }
        retainPublicationClaim(identity, key)
        val (protectedBytes, protectedCount) = protectedUsage()
        if (protectedBytes + plain.size + 28 > MAX_DISK_BYTES || protectedCount >= MAX_RECORDS) {
            // Read caches can be session-only when the durable tier is full.
            // containsRetainedBody still reports false, so saved-view receipts
            // cannot claim persistence that did not happen.
            remember(identity, value)
            return
        }
        directory?.let { dir ->
            check(dir.isDirectory || dir.mkdirs())
            val target = File(dir, digest(identity) + ".record")
            val temp = File.createTempFile("record-", ".tmp", dir)
            try {
                temp.outputStream().use {
                    it.write(crypt(identity, plain, true))
                    it.flush()
                    it.fd.sync()
                }
                check(temp.renameTo(target)) { "Could not atomically retain GitHub task state." }
            } finally {
                temp.delete()
            }
            pruneCachedRecords(target)
        }
        remember(identity, value)
    }

    suspend fun remove(scope: String, key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(!CLAIM_KEY.matches(key)) { "Execution receipts require explicit app-data erasure; cache cleanup cannot remove them." }
            val identity = "$scope:$key"
            memoryCharacters -= values.remove(identity)?.toString()?.length ?: 0
            directory?.let { File(it, digest(identity) + ".record").delete() }
            Unit
        }
    }

    private fun protectedFile(identity: String): File? = directory?.let { File(File(it, "execution-receipts"), digest(identity) + ".record") }

    /** Reservations are installed before the publication fence and before any HTTP dispatch. */
    private suspend fun writeProtectedLocked(identity: String, key: String, value: JsonObject, plain: ByteArray) {
        require(plain.size + 28 <= MAX_PROTECTED_RECORD_BYTES) { "GITHUB_RECEIPT_CAPACITY: critical receipt exceeds its reserved allowance; reconcile the existing claim." }
        val target = protectedFile(identity)
        val running = value["status"] in setOf(kotlinx.serialization.json.JsonPrimitive("RUNNING"), kotlinx.serialization.json.JsonPrimitive("RECONCILE_REQUIRED"))
        val reservation = target?.let { File(it.parentFile, it.nameWithoutExtension + ".reserved") }
        val wasReserved = if (reservation == null) identity in reservations else reservation.isFile
        val priorBytes = target?.takeIf { it.isFile }?.length() ?: protectedValues[identity]?.toString()?.encodeToByteArray()?.size?.toLong()?.plus(28) ?: 0
        val priorCharge = maxOf(priorBytes, if (wasReserved) MAX_PROTECTED_RECORD_BYTES.toLong() else 0)
        val nextCharge = if (running) MAX_PROTECTED_RECORD_BYTES.toLong() else plain.size + 28L
        val (used, count) = protectedUsage()
        check((used - priorCharge + nextCharge <= protectedCapacityBytes || priorCharge > 0 && nextCharge <= priorCharge) && (priorCharge > 0 || count < MAX_RECORDS)) {
            "GITHUB_RECEIPT_CAPACITY: durable critical-receipt capacity reached. Existing write receipts remain available; no new write was dispatched."
        }
        if (target == null) {
            retainPublicationClaim(identity, key)
            if (running) reservations += identity else reservations -= identity
            protectedValues[identity] = value
            return
        }
        val dir = requireNotNull(target.parentFile)
        check(dir.isDirectory || dir.mkdirs())
        if (running && !wasReserved) {
            java.io.FileOutputStream(requireNotNull(reservation)).use { it.fd.sync() }
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
        pruneCachedRecords()
        // A full protected tier cannot issue a claim and cannot evict another
        // mutation's result. Completed rows release unused reserved bytes.
        retainPublicationClaim(identity, key)
        val temp = File.createTempFile("receipt-", ".tmp", dir)
        try {
            java.io.FileOutputStream(temp).use {
                it.write(crypt(identity, plain, true))
                it.fd.sync()
            }
            check(temp.renameTo(target)) { "Could not atomically retain critical GitHub receipt." }
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            if (!running) reservation?.delete()
            pruneCachedRecords()
        } finally {
            temp.delete()
        }
    }

    private fun pruneCachedRecords(keep: File? = null) {
        val dir = directory ?: return
        val (protectedBytes, protectedCount) = protectedUsage()
        val records = dir.listFiles { file -> file.extension == "record" }.orEmpty().sortedBy { it.lastModified() }
        var total = records.sumOf { it.length() }
        var count = records.size
        for (record in records) {
            if (total <= MAX_DISK_BYTES - protectedBytes && count <= MAX_RECORDS - protectedCount) break
            if (record == keep) continue
            val size = record.length()
            if (record.delete()) {
                total -= size
                count--
            }
        }
    }

    private fun protectedUsage(): Pair<Long, Int> {
        val dir = directory?.let { File(it, "execution-receipts") }
        if (dir == null) {
            val ids = protectedValues.keys + reservations
            return ids.sumOf { id ->
                maxOf(protectedValues[id]?.toString()?.encodeToByteArray()?.size?.toLong()?.plus(28) ?: 0, if (id in reservations) MAX_PROTECTED_RECORD_BYTES.toLong() else 0)
            } to ids.size
        }
        val files = dir.listFiles { file -> file.extension in setOf("record", "reserved") }.orEmpty().groupBy { it.nameWithoutExtension }
        return files.values.sumOf { group ->
            maxOf(group.filter { it.extension == "record" }.sumOf { it.length() }, if (group.any { it.extension == "reserved" }) MAX_PROTECTED_RECORD_BYTES.toLong() else 0)
        } to files.size
    }

    /** Upgrade old encrypted execution bodies before ordinary cache pruning can reach them. */
    private fun migrateLegacyReceipts() {
        if (legacyReceiptsMigrated) return
        val dir = directory
        if (dir != null) {
            val claims = File(dir, "publication-claims")
            val protected = File(dir, "execution-receipts")
            val records = dir.listFiles { file -> file.name.matches(Regex("[a-f0-9]{64}[.]record")) }.orEmpty()
            for ((prefix, group) in records.groupBy { it.name.take(2) }) {
                // At most one archive prefix is decoded at a time. Encrypted
                // bodies move unchanged; their identity/AAD remains identical.
                val archived = archivedClaims(claims, prefix)
                for (record in group) {
                    val id = record.nameWithoutExtension
                    if (!File(claims, "$id.claim").isFile && id !in archived) continue
                    check(protected.isDirectory || protected.mkdirs())
                    val target = File(protected, record.name)
                    check(record.length() <= MAX_PROTECTED_RECORD_BYTES) { "Legacy GitHub critical receipt exceeds its recovery bound; preserve it for reconciliation." }
                    if (!target.exists()) check(record.renameTo(target)) { "Could not protect the legacy GitHub execution receipt." }
                }
            }
            if (protected.isDirectory) {
                java.nio.channels.FileChannel.open(protected.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
                java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            }
        }
        legacyReceiptsMigrated = true
    }

    /** Cache cleanup removes bodies, never authority. Only explicit app-data erasure removes fences. */
    private fun claimCheckpoint(identity: String, key: String): JsonObject? {
        if (!CLAIM_KEY.matches(key)) return null
        val id = digest(identity)
        if (id !in publicationClaims && directory?.let { File(File(it, "publication-claims"), "$id.claim").isFile || archivedClaims(File(it, "publication-claims"), id.take(2)).contains(id) } != true) return null
        return buildJsonObject {
            put("schema_version", 1)
            put("execution_id", key.removePrefix("execution:"))
            put("status", "RECONCILE_REQUIRED")
            put("receipt_available", false)
            put("reason", "The publication fence remains, but its cached receipt is unavailable. Reconcile remote state; do not repeat this write.")
        }
    }

    private fun retainPublicationClaim(identity: String, key: String) {
        if (!CLAIM_KEY.matches(key)) return
        val id = digest(identity)
        if (id in publicationClaims) return
        val marker = directory?.let { File(it, "publication-claims") }?.let { dir ->
            check(dir.isDirectory || dir.mkdirs())
            File(dir, "$id.claim")
        }
        if (marker?.isFile != true && (marker == null || id !in archivedClaims(marker.parentFile!!, id.take(2)))) {
            var count = marker?.parentFile?.listFiles { file -> file.extension == "claim" }?.size ?: publicationClaims.size
            if (marker != null && count >= publicationClaimLimit) {
                compactClaims(marker.parentFile!!)
                count = marker.parentFile!!.listFiles { file -> file.extension == "claim" }?.size ?: 0
            }
            check(count < publicationClaimLimit) { "GITHUB_PUBLICATION_CLAIM_CAPACITY: durable write-fence capacity reached. Read-only operations remain available; no new write was dispatched. Existing fences were not discarded." }
            if (marker != null) {
                check(marker.createNewFile()) { "Publication fence changed; reconcile the saved execution before retrying." }
                java.io.FileOutputStream(marker, true).use { it.fd.sync() }
                java.nio.channels.FileChannel.open(marker.parentFile!!.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            }
        }
        publicationClaims += id
        if (directory != null) while (publicationClaims.size > 4096) publicationClaims.remove(publicationClaims.first())
    }

    private fun archivedClaims(dir: File, prefix: String): Set<String> {
        val file = File(dir, "$prefix.archive")
        if (!file.exists()) return emptySet()
        val lines = file.readLines()
        val body = lines.dropLast(1)
        check(body.all { it.matches(Regex("[a-f0-9]{64}")) } && lines.lastOrNull() == "sha256:" + digest(body.joinToString("\n"))) { "GITHUB_PUBLICATION_ARCHIVE_INVALID: write fences require recovery. No new write was dispatched." }
        return body.toSet()
    }

    /** Install and sync the complete deduplication index before deleting any individual marker. */
    private fun compactClaims(dir: File) {
        dir.listFiles { file -> file.name.matches(Regex("[a-f0-9]{64}[.]claim")) }.orEmpty().groupBy { it.name.take(2) }.forEach { (prefix, files) ->
            val ids = (archivedClaims(dir, prefix) + files.map { it.nameWithoutExtension }).sorted()
            val body = ids.joinToString("\n")
            val temp = File.createTempFile("claims-", ".tmp", dir)
            try {
                java.io.FileOutputStream(temp).use { stream ->
                    stream.write((body + "\nsha256:" + digest(body) + "\n").encodeToByteArray())
                    stream.fd.sync()
                }
                check(temp.renameTo(File(dir, "$prefix.archive"))) { "Could not archive publication fences safely." }
                java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
                files.forEach { check(it.delete()) { "Publication fence archived; marker cleanup needs retry." } }
            } finally {
                temp.delete()
            }
        }
    }

    suspend fun publicationCapacity(): JsonObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = directory?.let { File(it, "publication-claims") }
            buildJsonObject {
                put("active", dir?.listFiles { it.extension == "claim" }?.size ?: publicationClaims.size)
                put("active_capacity", publicationClaimLimit)
                put("archived", dir?.listFiles { it.extension == "archive" }?.sumOf { archivedClaims(dir, it.nameWithoutExtension).size } ?: 0)
                put("automatic_safe_compaction", directory != null)
                put("deduplication_preserved", true)
                put("critical_receipt_bytes", protectedUsage().first)
                put("critical_receipt_capacity_bytes", protectedCapacityBytes)
                put("critical_receipts_evictable", false)
            }
        }
    }

    private fun remember(identity: String, value: JsonObject) {
        memoryCharacters += value.toString().length - (values.put(identity, value)?.toString()?.length ?: 0)
        while (values.size > 256 || memoryCharacters > 24 * 1024 * 1024) memoryCharacters -= values.remove(values.keys.first())?.toString()?.length ?: 0
    }

    private suspend fun crypt(identity: String, bytes: ByteArray, encrypt: Boolean): ByteArray {
        val secrets = vault ?: return bytes
        val key = secrets.read(KEY) ?: if (encrypt) {
            ByteArray(32).also {
                SecureRandom().nextBytes(it)
                secrets.put(KEY, it)
            }
        } else {
            error("GitHub state key is unavailable.")
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (encrypt) {
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            } else {
                require(bytes.size >= 28)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            cipher.updateAAD(identity.encodeToByteArray())
            if (encrypt) cipher.iv + cipher.doFinal(bytes) else cipher.doFinal(bytes, 12, bytes.size - 12)
        } finally {
            key.fill(0)
        }
    }

    companion object {
        const val MAX_RECORD_BYTES = 7 * 1024 * 1024
        const val MAX_DISK_BYTES = 48L * 1024 * 1024
        const val MAX_RECORDS = 1536
        const val MAX_PUBLICATION_CLAIMS = 16_384
        const val MAX_PROTECTED_BYTES = 16L * 1024 * 1024
        const val MAX_PROTECTED_RECORD_BYTES = 160 * 1024
        private val CLAIM_KEY = Regex("execution:[a-f0-9]{64}")
        private const val KEY = "github-repository-state-v1"
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
