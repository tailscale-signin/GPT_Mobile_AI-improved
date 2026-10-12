package dev.chungjungsoo.gptmobile.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Avoid large Binder parcels. Files are created only after the user's explicit Copy action. */
object SafeClipboard {
    internal fun requiresFile(characters: Int): Boolean = characters > 32_768

    suspend fun copy(context: Context, text: String, label: String = "GPT Mobile text") {
        try {
            val asFile = requiresFile(text.length)
            val safeLabel = label.take(80)
            val clip = if (asFile) {
                withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "clipboard-exports").apply { check(mkdirs() || isDirectory) }
                    directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
                        if (index >= 7 || System.currentTimeMillis() - file.lastModified() > 86_400_000) file.delete()
                    }
                    val file = File.createTempFile("copied-", ".txt", directory)
                    try {
                        file.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        ClipData.newUri(context.contentResolver, safeLabel, uri)
                    } catch (error: Exception) {
                        file.delete()
                        throw error
                    }
                }
            } else {
                ClipData.newPlainText(safeLabel, text)
            }
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            withContext(Dispatchers.Main) {
                requireNotNull(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(clip)
                if (asFile) Toast.makeText(context, "Large text copied as a text file. Paste into an app that accepts text files, or use Export.", Toast.LENGTH_LONG).show()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "Could not copy this content. Use Export to save the full text.", Toast.LENGTH_LONG).show() }
        }
    }
}
