package dev.chungjungsoo.gptmobile.data.network

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

internal data class BoundedHttpResponse(val code: Int, val text: String) {
    val successful: Boolean get() = code in 200..299
}

/** Cancellation closes the socket while waiting for headers or reading a bounded body. */
internal suspend fun Call.awaitBoundedText(maxBytes: Int = 8 * 1024 * 1024): BoundedHttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { BoundedHttpResponse(it.code, it.body?.byteStream()?.use { input -> BoundedResponseText.read(input, maxBytes) }.orEmpty()) }
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}
