package dev.chungjungsoo.gptmobile.data.agent.tool

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class McpMediaStore @Inject constructor(@param:ApplicationContext private val context: Context) {
    @Synchronized fun materialize(result: CallToolResult): CallToolResult {
        val directory = File(context.cacheDir, "mcp-media").apply { mkdirs() }
        var remaining = 8 * 1024 * 1024
        return result.copy(
            content = result.content.map { block ->
                val data: String
                val mime: String
                when (block) {
                    is ImageContent -> {
                        data = block.data
                        mime = block.mimeType
                    }
                    is AudioContent -> {
                        data = block.data
                        mime = block.mimeType
                    }
                    else -> return@map block
                }
                val extension = TYPES[mime] ?: return@map TextContent("Unsupported media type: ${mime.take(64)}")
                if (data.length > 5_592_408) return@map TextContent("Media exceeds the 4 MiB item limit.")
                val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return@map TextContent("Invalid media encoding.")
                if (bytes.size > minOf(4 * 1024 * 1024, remaining)) return@map TextContent("Media exceeds the result limit.")
                if (mime.startsWith("image/")) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth.toLong() * options.outHeight > 16_000_000L) return@map TextContent("Image dimensions are unsupported.")
                }
                val existing = directory.listFiles().orEmpty().sortedBy(File::lastModified)
                var total = existing.sumOf(File::length)
                existing.forEach { file ->
                    if (total + bytes.size > 32 * 1024 * 1024 || System.currentTimeMillis() - file.lastModified() > 86_400_000) {
                        total -= file.length()
                        file.delete()
                    }
                }
                val file = File(directory, UUID.randomUUID().toString() + ".$extension")
                file.writeBytes(bytes)
                remaining -= bytes.size
                ResourceLink(name = "Tool media", uri = "gptmobile://media/${file.name}", mimeType = mime, size = bytes.size.toLong())
            }
        )
    }
    companion object {
        val TYPES = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "audio/wav" to "wav", "audio/mpeg" to "mp3", "audio/ogg" to "ogg")
    }
}
