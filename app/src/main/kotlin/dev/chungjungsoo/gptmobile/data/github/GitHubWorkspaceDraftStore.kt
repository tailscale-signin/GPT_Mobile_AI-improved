package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import android.util.AtomicFile
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

/** Private encrypted drafts are independent of commits and never replay writes on startup. */
@Singleton
class GitHubWorkspaceDraftStore @Inject constructor(@ApplicationContext context: Context, private val vault: SecretVault) {
    // Context resolves this directory with filesystem checks. Defer until read/write
    // enters Dispatchers.IO instead of doing it during Hilt construction on Main.
    private val directory by lazy { File(context.noBackupFilesDir, "github-workspace-drafts") }
    private val mutex = Mutex()
    private fun file(connection: String): AtomicFile = AtomicFile(
        File(
            directory,
            MessageDigest.getInstance("SHA-256").digest(connection.toByteArray()).joinToString("") { "%02x".format(it) } + ".draft"
        )
    )

    suspend fun read(connection: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val atomic = file(connection)
            val bytes = try {
                atomic.openRead().use { input ->
                    require(input.channel.size() <= 8 * 1024 * 1024) { "Workspace draft exceeds its storage budget." }
                    input.readBytes()
                }
            } catch (_: java.io.FileNotFoundException) {
                return@withLock null
            }
            val key = requireNotNull(vault.read(portableKeyReference(atomic.baseFile.name)) ?: vault.read(KEY)) { "Workspace draft key is unavailable; encrypted drafts are preserved." }
            try {
                require(bytes.size >= 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                cipher.updateAAD(connection.toByteArray())
                val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
                try {
                    plain.decodeToString()
                } finally {
                    plain.fill(0)
                }
            } finally {
                key.fill(0)
            }
        }
    }

    suspend fun write(connection: String, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = vault.read(portableKeyReference(file(connection).baseFile.name)) ?: vault.read(KEY) ?: ByteArray(32).also {
                SecureRandom().nextBytes(it)
                vault.put(KEY, it)
            }
            val plain = value.encodeToByteArray()
            try {
                require(plain.size <= 8 * 1024 * 1024 - 28) { "Workspace draft exceeds its storage budget." }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                cipher.updateAAD(connection.toByteArray())
                val encrypted = cipher.doFinal(plain)
                check(directory.isDirectory || directory.mkdirs())
                val atomic = file(connection)
                val output = atomic.startWrite()
                try {
                    output.write(cipher.iv)
                    output.write(encrypted)
                    atomic.finishWrite(output)
                } catch (error: Throwable) {
                    atomic.failWrite(output)
                    throw error
                }
            } finally {
                plain.fill(0)
                key.fill(0)
            }
        }
    }

    companion object {
        const val KEY = "workspace-draft-encryption-v1"
        internal const val FILE_KEY_PREFIX = "workspace-draft-file-"
        internal fun portableKeyReference(fileName: String): String {
            require(fileName.matches(Regex("[a-f0-9]{64}\\.draft")))
            return FILE_KEY_PREFIX + fileName.removeSuffix(".draft")
        }
        internal fun isDraftKey(reference: String): Boolean = reference == KEY || reference.startsWith(FILE_KEY_PREFIX)
    }
}
