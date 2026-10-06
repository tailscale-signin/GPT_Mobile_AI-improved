package dev.chungjungsoo.gptmobile.data.chat

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Lossless archive storage. Short or incompressible text stays in its legacy format. */
object ArchivedTextCodec {
    const val PREFIX = "@gpt-archive:gzip:v1:"
    private const val MAX_CHARACTERS = 32_000_000

    fun encode(value: String): String {
        if (value.length < 512 || value.length > MAX_CHARACTERS) return value
        val bytes = value.toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        object : GZIPOutputStream(output) {
            init {
                def.setLevel(Deflater.BEST_COMPRESSION)
            }
        }.use { it.write(bytes) }
        val compressed = PREFIX + value.length + ":" + Base64.getEncoder().encodeToString(output.toByteArray())
        return if (compressed.toByteArray(Charsets.UTF_8).size < bytes.size) compressed else value
    }

    fun decode(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val payload = value.removePrefix(PREFIX)
            val length = payload.substringBefore(':').toInt()
            require(length in 0..MAX_CHARACTERS)
            val bytes = Base64.getDecoder().decode(payload.substringAfter(':'))
            val output = ByteArrayOutputStream()
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= length.toLong() * 4)
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name()).also { require(it.length == length) }
        }.getOrDefault(value)
    }
}

fun MessageV2.decodedArchiveText(): MessageV2 = if (!content.startsWith(ArchivedTextCodec.PREFIX) && !thoughts.startsWith(ArchivedTextCodec.PREFIX)) {
    this
} else {
    copy(content = ArchivedTextCodec.decode(content), thoughts = ArchivedTextCodec.decode(thoughts))
}
fun ToolEvent.decodedArchiveText(): ToolEvent = if (!arguments.startsWith(ArchivedTextCodec.PREFIX) && result?.startsWith(ArchivedTextCodec.PREFIX) != true && error?.startsWith(ArchivedTextCodec.PREFIX) != true) {
    this
} else {
    copy(arguments = ArchivedTextCodec.decode(arguments), result = result?.let(ArchivedTextCodec::decode), error = error?.let(ArchivedTextCodec::decode))
}
