package dev.chungjungsoo.gptmobile.presentation.ui.setting

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Public GitHub avatar only; no shared client or authorization header is used. */
@Composable
internal fun GitHubAccountAvatar(url: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            try {
                GitHubAvatarImages.load(url)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
    val image = bitmap
    if (image == null) Icon(Icons.Rounded.Person, null, modifier) else Image(image, null, modifier, contentScale = ContentScale.Crop)
}

private object GitHubAvatarImages {
    private val images = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 32
    }
    private val http by lazy {
        HttpClient(OkHttp) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 5000
                connectTimeoutMillis = 3000
                socketTimeoutMillis = 3000
            }
        }
    }

    suspend fun load(url: String): ImageBitmap? {
        if (url.length > 1024) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "avatars.githubusercontent.com" || uri.port != -1 || uri.userInfo != null || uri.fragment != null) return null
        synchronized(images) { images[url] }?.let { return it }
        val response = http.get(url)
        if (!response.status.isSuccess()) return null
        val channel = response.bodyAsChannel()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            if (read == 0) {
                yield()
                continue
            }
            if (output.size() + read > 128 * 1024) {
                channel.cancel(null)
                return null
            }
            output.write(buffer, 0, read)
        }
        val bytes = output.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 1024 * 1024) return null
        var sample = 1
        while (bounds.outWidth / sample > 128 || bounds.outHeight / sample > 128) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() ?: return null
        synchronized(images) { images[url] = bitmap }
        return bitmap
    }
}
