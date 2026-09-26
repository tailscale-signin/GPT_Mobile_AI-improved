package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.presentation.theme.GPTMobileTheme
import java.io.File

class McpMediaActivity : ComponentActivity() {
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
        val image = if (safe && file.exists() && file.extension in setOf("png", "jpg", "webp")) BitmapFactory.decodeFile(file.path) else null
        setContent {
            GPTMobileTheme(themeMode = dev.chungjungsoo.gptmobile.data.model.ThemeMode.SYSTEM) {
                Column(Modifier.padding(20.dp)) {
                    TextButton(onClick = ::finish) { Text("Back") }
                    when {
                        !safe || !file.exists() -> Text("This temporary media result has expired.")
                        image != null -> Image(image.asImageBitmap(), "Media returned by the MCP tool", Modifier.fillMaxWidth())
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
                        image?.recycle()
                    }
                }
            }
        }
    }
}
