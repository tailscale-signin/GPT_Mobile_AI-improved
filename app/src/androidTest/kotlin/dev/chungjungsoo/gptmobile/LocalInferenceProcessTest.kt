package dev.chungjungsoo.gptmobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferenceService
import dev.chungjungsoo.gptmobile.data.localruntime.ipc.RuntimeWire
import dev.chungjungsoo.gptmobile.data.localruntime.ipc.RuntimeWireTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an Android device; this is not an NPU qualification test. */
@RunWith(AndroidJUnit4::class)
class LocalInferenceProcessTest {
    @Test fun workerIsPrivateAndCannotRunInTheUiProcess() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(android.content.ComponentName(context, LocalInferenceService::class.java), 0)
        assertFalse(service.exported)
        assertEquals(context.packageName + ":local_inference", service.processName)
    }

    @Test fun largePayloadUsesDescriptorAndPreservesUnicode() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val thread = android.os.HandlerThread("ipc-test").apply { start() }
        val bound = java.util.concurrent.CompletableFuture<android.os.Messenger>()
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {
                bound.complete(android.os.Messenger(service))
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
        }
        try {
            assertTrue(context.bindService(android.content.Intent(context, LocalInferenceService::class.java), connection, Context.BIND_AUTO_CREATE))
            val received = java.util.concurrent.LinkedBlockingQueue<RuntimeWire>()
            val replies = android.os.Messenger(object : android.os.Handler(thread.looper) {
                override fun handleMessage(message: android.os.Message) {
                    received.put(RuntimeWireTransport.read(message))
                }
            })
            val packet = RuntimeWire("PING", "test", text = "é🙂".repeat(20_000))
            RuntimeWireTransport.send(context, bound.get(10, java.util.concurrent.TimeUnit.SECONDS), packet, replies)
            val reply = received.poll(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(packet.text, reply?.text)
            assertEquals("RESULT", reply?.operation)
            assertTrue(reply?.generation?.isNotBlank() == true)
            val directory = java.io.File(context.noBackupFilesDir, "runtime-ipc-${android.os.Process.myPid()}")
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            val worker = bound.get()
            repeat(10) { index ->
                // The next request starts immediately after the previous terminal packet.
                RuntimeWireTransport.send(context, worker, RuntimeWire("PING", "next-$index", generation = reply!!.generation, text = "next"), replies)
                assertEquals("RESULT", received.poll(10, java.util.concurrent.TimeUnit.SECONDS)?.operation)
            }
            RuntimeWireTransport.send(context, worker, RuntimeWire("PING", "stale", generation = "old-generation"), replies)
            assertEquals("ERROR", received.poll(10, java.util.concurrent.TimeUnit.SECONDS)?.operation)
            val died = java.util.concurrent.CountDownLatch(1)
            worker.binder.linkToDeath({ died.countDown() }, 0)
            RuntimeWireTransport.send(context, worker, RuntimeWire("SHUTDOWN", "death-test"), replies)
            assertTrue("Only the private worker should terminate", died.await(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            context.unbindService(connection)
            thread.quitSafely()
        }
    }
}
