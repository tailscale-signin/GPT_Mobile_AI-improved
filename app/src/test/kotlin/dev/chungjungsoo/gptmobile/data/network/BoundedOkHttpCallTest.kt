package dev.chungjungsoo.gptmobile.data.network

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOkHttpCallTest {
    private class PendingCall : Call {
        var callback: Callback? = null
        var cancelled = false
        override fun request(): Request = Request.Builder().url("https://example.org/").build()
        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }
        override fun execute(): Response = error("Must enqueue asynchronously")
        override fun cancel() {
            cancelled = true
        }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = PendingCall()
    }

    @Test fun cancellingWhileWaitingForHeadersCancelsTheSocketAndAcceptsLateFailure() = runBlocking {
        val call = PendingCall()
        val task = async(start = CoroutineStart.UNDISPATCHED) { call.awaitBoundedText() }
        task.cancelAndJoin()
        assertTrue(call.cancelled)
        call.callback!!.onFailure(call, IOException("socket cancelled"))
    }

    @Test fun oversizedBodyIsClosedAndReportsBoundedFailure() = runBlocking {
        val call = PendingCall()
        var closed = false
        val body = object : ResponseBody() {
            private val data = object : ForwardingSource(Buffer().writeUtf8("123456789")) {
                override fun close() {
                    closed = true
                    super.close()
                }
            }.buffer()
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = data
        }
        val task = async(start = CoroutineStart.UNDISPATCHED) { runCatching { call.awaitBoundedText(8) } }
        call.callback!!.onResponse(call, Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build())
        assertTrue(task.await().exceptionOrNull() is IllegalArgumentException)
        assertTrue(closed)
        assertEquals(false, call.cancelled)
    }
}
