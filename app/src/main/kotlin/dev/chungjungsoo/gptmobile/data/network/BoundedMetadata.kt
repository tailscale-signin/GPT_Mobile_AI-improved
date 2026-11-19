package dev.chungjungsoo.gptmobile.data.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.yield

/** Size bounds apply while reading, before JSON allocation and parsing. */
internal suspend fun HttpResponse.boundedMetadata(maxBytes: Int = 2_000_000): String =
    boundedResponseText(maxBytes, "Catalog response exceeds its size limit.")

internal suspend fun HttpResponse.boundedResponseText(maxBytes: Int = 16 * 1024 * 1024, limitMessage: String = "Provider response exceeds its size limit."): String {
    val channel = bodyAsChannel()
    try {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = channel.readAvailable(buffer, 0, buffer.size)
            if (count < 0) break
            if (count == 0) {
                yield()
                continue
            }
            check(output.size() + count <= maxBytes) { limitMessage }
            output.write(buffer, 0, count)
        }
        return output.toString("UTF-8")
    } finally {
        channel.cancel(null)
    }
}
