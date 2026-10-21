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

/** Credential-scoped encrypted durable state; temporary sessions use a private memory store. */
@Singleton
class GitHubRepositoryStateStore private constructor(private val directory: File?, private val vault: SecretVault?) {
    @Inject constructor(@ApplicationContext context: Context, vault: SecretVault) : this(File(context.noBackupFilesDir, "github-repository-state"), vault)
    constructor(directory: File? = null) : this(directory, null)
    private val mutex = Mutex()
    private val values = LinkedHashMap<String, JsonObject>(16, 0.75f, true)
    private var memoryCharacters = 0
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Existence only: index coverage must not decode every retained source body. */
    suspend fun contains(scope: String, key: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val identity = "$scope:$key"
            values.containsKey(identity) || directory?.let { File(it, digest(identity) + ".record") }?.let { it.isFile && it.length() in 1..MAX_RECORD_BYTES.toLong() } == true
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
        val identity = "$scope:$key"
        values[identity]?.let { return it }
        val file = directory?.let { File(it, digest(identity) + ".record") } ?: return null
        if (!file.isFile || file.length() > MAX_RECORD_BYTES) return null
        val raw = file.readBytes()
        val value = runCatching { json.parseToJsonElement(crypt(identity, raw, false).decodeToString()) as? JsonObject }.getOrNull() ?: return null
        remember(identity, value)
        file.setLastModified(System.currentTimeMillis())
        return value
    }

    private suspend fun writeLocked(scope: String, key: String, value: JsonObject) {
        val identity = "$scope:$key"
        val plain = value.toString().encodeToByteArray()
        require(plain.size <= MAX_RECORD_BYTES - 28) { "GitHub state record exceeds its byte budget." }
        directory?.let { dir ->
            check(dir.isDirectory || dir.mkdirs())
            val target = File(dir, digest(identity) + ".record")
            val temp = File.createTempFile("record-", ".tmp", dir)
            try {
                temp.outputStream().use {
                    it.write(crypt(identity, plain, true))
                    it.flush()
                }
                check(temp.renameTo(target)) { "Could not atomically retain GitHub task state." }
            } finally {
                temp.delete()
            }
            val records = dir.listFiles { f -> f.extension == "record" }.orEmpty().sortedBy { it.lastModified() }
            var total = records.sumOf { it.length() }
            var count = records.size
            for (record in records) {
                if (total <= MAX_DISK_BYTES && count <= MAX_RECORDS) break
                if (record == target) continue
                val size = record.length()
                if (record.delete()) {
                    total -= size
                    count--
                }
            }
        }
        remember(identity, value)
    }

    suspend fun remove(scope: String, key: String) = mutex.withLock {
        val identity = "$scope:$key"
        memoryCharacters -= values.remove(identity)?.toString()?.length ?: 0
        directory?.let { File(it, digest(identity) + ".record").delete() }
        Unit
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
        private const val KEY = "github-repository-state-v1"
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
