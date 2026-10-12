package dev.chungjungsoo.gptmobile.data.network

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object BoundedResponseText {
    fun read(input: InputStream, maxBytes: Int): String {
        require(maxBytes > 0)
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(output.size().toLong() + count <= maxBytes) { "Response exceeded the configured download limit." }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
