package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.util.FileUtils
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add one selected page to the regular image composer. Only sending the draft invokes a model. */
@Composable
internal fun rememberScanPagePicker(chatRoom: StateFlow<ChatRoomV2>, attach: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var room by rememberSaveable { mutableStateOf(0) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf("1") }
    var message by rememberSaveable { mutableStateOf("") }
    var rendering by androidx.compose.runtime.remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (room == chatRoom.value.id && uri != null) {
            source = uri.toString()
            page = "1"
            message = ""
        }
    }
    val current = source
    if (current != null && room == chatRoom.value.id) {
        AlertDialog(
            onDismissRequest = { if (!rendering) source = null },
            title = { Text("Attach scanned page") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Choose a page to attach as an image. Ask your selected vision model to transcribe or describe it.")
                    OutlinedTextField(value = page, onValueChange = { if (it.length <= 7 && it.all(Char::isDigit)) page = it }, label = { Text("Page number") }, singleLine = true, enabled = !rendering)
                    if (message.isNotEmpty()) Text(message)
                }
            },
            confirmButton = {
                TextButton(enabled = !rendering && (page.toIntOrNull() ?: 0) > 0, onClick = {
                    val number = page.toInt()
                    val owner = room
                    rendering = true
                    message = "Preparing page…"
                    scope.launch {
                        var result: File? = null
                        try {
                            withContext(Dispatchers.IO) { result = renderScanPage(context, Uri.parse(current), number) }
                            if (chatRoom.value.id == owner) {
                                attach(requireNotNull(result).absolutePath)
                                result = null
                            }
                            source = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            message = if (error is ScanPageException) error.message.orEmpty() else "Could not read this PDF. Reopen the file or choose an unlocked copy."
                        } finally {
                            result?.delete()
                            rendering = false
                        }
                    }
                }) { Text("Add to draft") }
            },
            dismissButton = { TextButton(enabled = !rendering, onClick = { source = null }) { Text("Cancel") } }
        )
    }
    return {
        if (!rendering && chatRoom.value.id > 0) {
            room = chatRoom.value.id
            source = null
            picker.launch("application/pdf")
        }
    }
}

private class ScanPageException(message: String) : Exception(message)

internal fun renderScanPage(context: Context, uri: Uri, number: Int): File {
    val local = File.createTempFile("scan-", ".pdf", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            local.outputStream().use { output ->
                val buffer = ByteArray(32768)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > FileUtils.MAX_UPLOAD_SIZE_BYTES) throw ScanPageException("Choose a PDF smaller than 50 MB.")
                    output.write(buffer, 0, n)
                }
            }
        } ?: throw ScanPageException("The selected file is unavailable.")
        PDDocument.load(local, MemoryUsageSetting.setupMixed(8L * 1024 * 1024)).use {
            if (!it.currentAccessPermission.canExtractContent()) throw ScanPageException("This PDF restricts extraction.")
        }
        ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (number !in 1..renderer.pageCount) throw ScanPageException("Choose a page from 1 to " + renderer.pageCount + ".")
                renderer.openPage(number - 1).use { page ->
                    val scale = 1800.0 / maxOf(page.width, page.height).coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap((page.width * scale).roundToInt().coerceIn(1, 1800), (page.height * scale).roundToInt().coerceIn(1, 1800), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val dir = File(context.filesDir, "attachments").also { it.mkdirs() }
                        val target = File.createTempFile("scan-page-$number-", ".png", dir)
                        try {
                            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            return target
                        } catch (error: Throwable) {
                            target.delete()
                            throw error
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    } finally {
        local.delete()
    }
}
