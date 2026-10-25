package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class GgufMetadataCoalescingTest {
    private fun fixture(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            fun u32(value: Int) = out.writeInt(Integer.reverseBytes(value))
            fun u64(value: Long) = out.writeLong(java.lang.Long.reverseBytes(value))
            fun text(value: String) {
                val data = value.toByteArray()
                u64(data.size.toLong())
                out.write(data)
            }
            out.writeBytes("GGUF")
            u32(3)
            u64(1)
            u64(2)
            text("tokenizer.ggml.tokens")
            u32(9)
            u32(8)
            u64(128)
            repeat(128) { text("vocabulary $it") }
            text("general.architecture")
            u32(8)
            text("gemma")
        }
    }.toByteArray()

    @Test fun concurrentStrongIdentityMissesShareOneParsedResultWithoutHashingTheWeights() {
        val file = File.createTempFile("gguf-coalesced", ".gguf").apply { writeBytes(fixture()) }
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            assumeNotNull(PackageDigest.installedFileIdentity(file))
            val hashedBefore = PackageDigest.bytesHashed.get()
            val owner = executor.submit<Map<String, String>> {
                GgufImportAdapter.inspect(file) {
                    if (calls.incrementAndGet() == 3) {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val waiterCalls = AtomicInteger()
            val waiter = executor.submit<Map<String, String>> {
                GgufImportAdapter.inspect(file) { if (waiterCalls.incrementAndGet() == 2) waiting.countDown() }
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            release.countDown()
            val first = owner.get(5, TimeUnit.SECONDS)
            assertSame(first, waiter.get(5, TimeUnit.SECONDS))
            assertSame(first, GgufImportAdapter.inspect(file))
            assertEquals(mapOf("general.architecture" to "gemma"), first)
            assertEquals(hashedBefore, PackageDigest.bytesHashed.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
            file.delete()
        }
    }

    @Test fun aCancelledWaiterCannotCancelTheSharedInspection() {
        val file = File.createTempFile("gguf-waiter", ".gguf").apply { writeBytes(fixture()) }
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            assumeNotNull(PackageDigest.installedFileIdentity(file))
            val owner = executor.submit<Map<String, String>> {
                GgufImportAdapter.inspect(file) {
                    if (calls.incrementAndGet() == 3) {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val canceled = executor.submit<Boolean> {
                var checks = 0
                try {
                    GgufImportAdapter.inspect(file) { if (++checks > 1) throw CancellationException("This caller stopped") }
                    false
                } catch (_: CancellationException) {
                    true
                }
            }
            assertTrue(canceled.get(5, TimeUnit.SECONDS))
            release.countDown()
            assertSame(owner.get(5, TimeUnit.SECONDS), GgufImportAdapter.inspect(file))
        } finally {
            release.countDown()
            executor.shutdownNow()
            file.delete()
        }
    }
}
