package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import dev.chungjungsoo.gptmobile.presentation.ui.setting.McpMediaActivity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalChatMediaPreview = staticCompositionLocalOf<((Uri) -> Unit)?> { null }

@Composable
internal fun chatMediaPreview(viewModel: ChatViewModel): (Uri) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRoom by rememberSaveable { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = pendingUri
        val room = pendingRoom
        pendingUri = null
        pendingRoom = 0
        val returned = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && returned != null && returned.toString() == expected && room > 0) {
            val name = returned.lastPathSegment.orEmpty()
            scope.launch {
                if (viewModel.chatRoom.first { it.id > 0 }.id != room) return@launch
                var staged: File? = null
                try {
                    withContext(Dispatchers.IO) {
                        staged = copyReplyImage(File(context.cacheDir, "mcp-media"), File(context.filesDir, "attachments"), name)
                    }
                    if (viewModel.chatRoom.value.id == room) {
                        val file = staged
                        if (file == null) {
                            viewModel.notifyAttachmentCopyFailed()
                        } else {
                            viewModel.addSelectedFile(file.absolutePath)
                            staged = null
                        }
                    }
                } finally {
                    staged?.delete()
                }
            }
        }
    }
    return { uri ->
        val room = viewModel.chatRoom.value.id
        val preview = Intent(context, McpMediaActivity::class.java).setData(uri)
        if (room > 0 && pendingUri == null) {
            pendingUri = uri.toString()
            pendingRoom = room
            launcher.launch(preview.putExtra(McpMediaActivity.ALLOW_REPLY, true))
        } else if (pendingUri == null) {
            context.startActivity(preview)
        }
    }
}
