package dev.chungjungsoo.gptmobile.data.sharing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class IncomingShare(val text: String, val files: List<String> = emptyList(), val expiresAt: Long = System.currentTimeMillis() + 600_000L)

@Singleton
class ShareInbox @Inject constructor(@ApplicationContext private val context: Context, private val vault: SecretVault) {
    suspend fun import(intent: Intent): IncomingShare = withContext(Dispatchers.IO) {
        require(intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT)) { "Unsupported sharing action." }
        val text = intent.getCharSequenceExtra(if (intent.action == Intent.ACTION_PROCESS_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT)?.toString().orEmpty()
        require(text.length <= 16000) { "Share at most 16,000 characters at a time." }
        val uris = buildList {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            addAll(IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
            intent.clipData?.let { clips -> for (i in 0 until minOf(clips.itemCount, 11)) clips.getItemAt(i).uri?.let(::add) }
        }.distinct()
        require(uris.size <= 10) { "Share at most 10 files at a time." }
        val root = File(context.filesDir, "share-inbox").also { it.mkdirs() }
        val folder = File(root, UUID.randomUUID().toString()).also { it.mkdirs() }
        var total = 0L
        try {
            val files = uris.mapIndexed { index, uri ->
                require(uri.scheme == "content" && uri.authority != "${context.packageName}.fileprovider") { "Only granted files from another app can be imported." }
                val mime = context.contentResolver.getType(uri).orEmpty()
                val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) } ?: "bin"
                val file = File(folder, "shared-${index + 1}.$extension")
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "The shared file is unavailable." }
                    file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var fileSize = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            fileSize += count
                            require(fileSize <= 25L * 1024 * 1024 && total <= 50L * 1024 * 1024) { "Limit: 25 MB per file, 50 MB per share." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                file.path
            }
            require(text.isNotBlank() || files.isNotEmpty()) { "Nothing was shared." }
            IncomingShare(text, files)
        } catch (error: Throwable) {
            folder.deleteRecursively()
            throw error
        }
    }

    suspend fun stage(share: IncomingShare): String {
        val token = UUID.randomUUID().toString()
        val bytes = Json.encodeToString(share).encodeToByteArray()
        try {
            vault.put("share-$token", bytes)
        } finally {
            bytes.fill(0)
        }
        return token
    }

    suspend fun consume(token: String): IncomingShare? {
        if (!token.matches(Regex("[a-f0-9-]{36}"))) return null
        val bytes = vault.read("share-$token") ?: return null
        val share = try {
            Json.decodeFromString<IncomingShare>(bytes.decodeToString())
        } finally {
            bytes.fill(0)
        }

        val root = File(context.filesDir, "share-inbox").canonicalFile
        return share.takeIf { it.expiresAt > System.currentTimeMillis() && it.files.all { path -> File(path).canonicalFile.toPath().startsWith(root.toPath()) && File(path).isFile } }
    }

    suspend fun acknowledge(token: String) {
        vault.delete("share-$token")
    }

    suspend fun discard(share: IncomingShare) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "share-inbox").canonicalFile.toPath()
        share.files.forEach { path -> File(path).takeIf { it.canonicalFile.toPath().startsWith(root) }?.delete() }
    }
}
