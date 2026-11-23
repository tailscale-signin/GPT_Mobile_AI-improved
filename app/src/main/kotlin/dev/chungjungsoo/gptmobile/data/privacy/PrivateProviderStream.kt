package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.BoundedResponseText
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Uses the already-verified client, preserving its last-moment route and authority interceptor. */
internal object PrivateProviderStream {
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_EVENT_BYTES = 256 * 1024

    fun read(client: OkHttpClient, request: Request, kind: ClientType) = callbackFlow {
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(if (e is PrivateDispatchPolicyChanged) e else PrivateProviderResponseException(PrivateResponseFailure.NETWORK))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw PrivateProviderResponseException(PrivateResponseFailure.HTTP_STATUS, it.code)
                        val body = it.body
                        fun publish(frame: PrivateProviderFrame) {
                            if (trySendBlocking(frame).isFailure) throw PrivateProviderResponseException(PrivateResponseFailure.NETWORK)
                        }
                        val decoder = PrivateProviderResponse(kind, ::publish)
                        if (body.contentType()?.toString()?.substringBefore(';')?.trim()?.equals("text/event-stream", ignoreCase = true) == true) {
                            publish(PrivateProviderFrame.Delivery(PrivateDeliveryMode.INCREMENTAL))
                            body.byteStream().buffered().use { input -> readEvents(input, decoder) }
                        } else {
                            val mode = if (kind == ClientType.LLAMA || kind == ClientType.OLLAMA) PrivateDeliveryMode.COMPLETION_BUFFERED_ENDPOINT else PrivateDeliveryMode.COMPLETION_BUFFERED_PROVIDER
                            publish(PrivateProviderFrame.Delivery(mode))
                            val text = try {
                                body.byteStream().use { input -> BoundedResponseText.read(input, MAX_RESPONSE_BYTES) }
                            } catch (_: IllegalArgumentException) {
                                throw PrivateProviderResponseException(PrivateResponseFailure.RESPONSE_LIMIT)
                            }
                            decoder.completedJson(text)
                        }
                    }
                    close()
                } catch (failure: PrivateProviderResponseException) {
                    close(failure)
                } catch (_: Exception) {
                    close(PrivateProviderResponseException(PrivateResponseFailure.NETWORK))
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(1)

    /** Byte limits cover comments and delimiters as well as data, with at most one event buffered. */
    internal fun readEvents(input: InputStream, decoder: PrivateProviderResponse) {
        var total = 0
        val data = StringBuilder()
        var eventBytes = 0
        fun event(): Boolean {
            if (data.isEmpty()) return false
            val terminal = decoder.event(data.toString())
            data.clear()
            eventBytes = 0
            return terminal
        }
        while (true) {
            val bytes = ByteArrayOutputStream()
            var ended = false
            while (true) {
                val value = input.read()
                if (value < 0) {
                    ended = true
                    break
                }
                if (++total > MAX_RESPONSE_BYTES || bytes.size() >= MAX_EVENT_BYTES) throw PrivateProviderResponseException(PrivateResponseFailure.RESPONSE_LIMIT)
                if (value == '\n'.code) break
                bytes.write(value)
            }
            if (ended && bytes.size() == 0) {
                event()
                break
            }
            val line = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString().removeSuffix("\r")
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw PrivateProviderResponseException(PrivateResponseFailure.MALFORMED_EVENT)
            }
            if (line.isEmpty()) {
                if (event()) break
            } else if (line.startsWith("data:")) {
                eventBytes += bytes.size()
                if (eventBytes > MAX_EVENT_BYTES) throw PrivateProviderResponseException(PrivateResponseFailure.RESPONSE_LIMIT)
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
            if (ended) {
                event()
                break
            }
        }
        decoder.end()
    }
}
