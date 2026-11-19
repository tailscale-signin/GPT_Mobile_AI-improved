package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import dev.chungjungsoo.gptmobile.util.FileUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MediaPreview(val available: Boolean, val image: Bitmap?)

internal fun decodeMediaPreview(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = FileUtils.calculateImageInSampleSize(bounds.outWidth, bounds.outHeight)
    }
    return BitmapFactory.decodeFile(file.path, options)
}

class McpMediaActivity : dev.chungjungsoo.gptmobile.presentation.ui.main.ProtectedActivity() {
    companion object {
        internal const val ALLOW_REPLY = "dev.chungjungsoo.gptmobile.media.ATTACH_REPLY"
    }

    private var player: MediaPlayer? = null
    override fun onStop() {
        player?.pause()
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.data?.lastPathSegment.orEmpty()
        val safe = name.matches(Regex("[a-f0-9-]{36}\\.(png|jpg|webp|mp3|wav|ogg)"))
        val file = File(cacheDir, "mcp-media/$name")
        setContent {
            val preview by produceState<MediaPreview?>(initialValue = null, key1 = file) {
                value = withContext(Dispatchers.IO) {
                    val available = safe && file.isFile
                    val image = if (available && file.extension in setOf("png", "jpg", "webp")) decodeMediaPreview(file) else null
                    MediaPreview(available, image)
                }
            }
            val image = preview?.image
            GPTMobileTheme(themeMode = dev.chungjungsoo.gptmobile.data.model.ThemeMode.SYSTEM) {
                Column(Modifier.padding(20.dp)) {
                    TextButton(onClick = ::finish) { Text("Back") }
                    if (preview?.available == true) {
                        val scope = rememberCoroutineScope()
                        val mime = dev.chungjungsoo.gptmobile.data.agent.tool.McpMediaStore.TYPES.entries.firstOrNull { it.value == file.extension }?.key
                        if (mime != null) {
                            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { destination ->
                                if (destination != null) {
                                    scope.launch {
                                        val written = withContext(Dispatchers.IO) {
                                            try {
                                                file.inputStream().use { input -> contentResolver.openOutputStream(destination, "wt")?.use { output -> input.copyTo(output) } } != null
                                            } catch (_: java.io.IOException) {
                                                false
                                            } catch (_: SecurityException) {
                                                false
                                            }
                                        }
                                        android.widget.Toast.makeText(this@McpMediaActivity, if (written) "File saved" else "Could not save media", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            if (image != null && intent.getBooleanExtra(ALLOW_REPLY, false)) {
                                TextButton(onClick = {
                                    setResult(RESULT_OK, android.content.Intent().setData(intent.data))
                                    finish()
                                }) { Text("Attach to reply") }
                            }
                            TextButton(onClick = { save.launch("chat-media." + file.extension) }) { Text("Save a copy") }
                        }
                    }
                    when {
                        preview == null -> Text("Loading preview…")
                        preview?.available != true -> Text("This temporary media result has expired.")
                        image != null -> ZoomableMediaImage(image)
                        file.extension in setOf("mp3", "wav", "ogg") -> TextButton(onClick = {
                            runCatching {
                                if (player == null) {
                                    player = MediaPlayer().apply {
                                        setDataSource(file.path)
                                        prepare()
                                    }
                                }
                                player?.let { if (it.isPlaying) it.pause() else it.start() }
                            }
                        }) { Text("Play / pause audio") }
                        else -> Text("Unable to preview this media.")
                    }
                }
                DisposableEffect(Unit) {
                    onDispose {
                        player?.release()
                        player = null
                    }
                }
            }
        }
    }
}
