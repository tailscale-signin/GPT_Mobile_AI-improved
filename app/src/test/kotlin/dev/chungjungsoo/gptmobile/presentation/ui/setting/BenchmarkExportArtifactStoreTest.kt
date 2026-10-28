package dev.chungjungsoo.gptmobile.presentation.ui.setting

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BenchmarkExportArtifactStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store() = BenchmarkExportArtifactStore(directory = { File(temporary.root, "private-exports") }, now = { 1000L })

    @Test fun completeExportCopiesInBoundedChunksWithoutTruncatingItsFinalRecord(): Unit = runBlocking {
        val artifacts = store()
        val chunk = ("[\"source Ω 文\",1234567890]," + " ".repeat(4096)).toByteArray()
        val beginning = "{\"records\":[".toByteArray()
        val tail = "[\"FINAL-RECORD\",7654321]]}".toByteArray()
        val artifact = artifacts.prepare(false) { output ->
            output.write(beginning)
            repeat(600) { output.write(chunk) }
            output.write(tail)
        }
        assertEquals(beginning.size + 600L * chunk.size + tail.size, artifact.bytes)
        assertEquals(artifact, artifacts.latest())
        val destination = File(temporary.root, "saved.json")
        var largestWrite = 0
        destination.outputStream().use { file ->
            val bounded = object : OutputStream() {
                override fun write(value: Int) = file.write(value)
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    largestWrite = maxOf(largestWrite, length)
                    file.write(buffer, offset, length)
                }
            }
            artifacts.copyTo(artifact, bounded)
        }
        assertEquals(artifact.bytes, destination.length())
        assertTrue(largestWrite in 1..64 * 1024)
        RandomAccessFile(destination, "r").use { file ->
            file.seek(file.length() - tail.size)
            val savedTail = ByteArray(tail.size)
            file.readFully(savedTail)
            assertTrue(tail.contentEquals(savedTail))
        }
        artifacts.complete(artifact)
        assertNull(artifacts.latest())
    }

    @Test fun failedDestinationRetainsTheExactCompleteArtifactForRetryAfterRestart(): Unit = runBlocking {
        val artifacts = store()
        val artifact = artifacts.prepare(true) { it.write("{\"lastRecord\":\"complete\"}".toByteArray()) }
        assertThrows(IOException::class.java) {
            runBlocking {
                artifacts.copyTo(
                    artifact,
                    object : OutputStream() {
                        override fun write(value: Int): Unit = throw IOException("destination full")
                    }
                )
            }
        }
        val restarted = store()
        assertEquals(artifact, restarted.find(artifact.id))
        val destination = File(temporary.root, "retry.json")
        destination.outputStream().use { restarted.copyTo(artifact, it) }
        assertEquals("{\"lastRecord\":\"complete\"}", destination.readText())
        restarted.complete(artifact)
        assertNull(restarted.latest())
    }

    @Test fun failedPreparationDoesNotReplaceAnEarlierCompleteExportOrAdvertiseAPartialFile(): Unit = runBlocking {
        val artifacts = store()
        val earlier = artifacts.prepare(false) { it.write("{\"complete\":true}".toByteArray()) }
        assertThrows(IOException::class.java) {
            runBlocking {
                artifacts.prepare(true) {
                    it.write("{\"partial\":".toByteArray())
                    throw IOException("snapshot failed")
                }
            }
        }
        assertEquals(earlier, artifacts.latest())
        assertEquals(1, File(temporary.root, "private-exports").listFiles().orEmpty().count { it.name.endsWith(".json") })
        assertFalse(File(temporary.root, "private-exports").listFiles().orEmpty().any { it.name.endsWith(".partial") })
    }

    @Test fun cancellationDuringDestinationCopyKeepsACompleteRetryReceipt(): Unit = runBlocking {
        val artifacts = store()
        val artifact = artifacts.prepare(false) { it.write("{\"complete\":true}".toByteArray()) }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                artifacts.copyTo(
                    artifact,
                    object : OutputStream() {
                        override fun write(value: Int): Unit = throw CancellationException("screen closed")
                    }
                )
            }
        }
        assertEquals(artifact, store().latest())
    }

    @Test fun destinationCloseFailureRetainsTheArtifactAndSuccessRetiresItOnlyAfterClose(): Unit = runBlocking {
        val artifacts = store()
        val artifact = artifacts.prepare(false) { it.write("{\"complete\":true}".toByteArray()) }
        assertThrows(IOException::class.java) {
            runBlocking {
                artifacts.saveTo(artifact) {
                    object : OutputStream() {
                        override fun write(value: Int) = Unit
                        override fun close(): Unit = throw IOException("provider close failed")
                    }
                }
            }
        }
        assertEquals(artifact, artifacts.find(artifact.id))
        var closed = false
        artifacts.saveTo(artifact) {
            object : OutputStream() {
                override fun write(value: Int) = Unit
                override fun close() {
                    assertTrue(File(File(temporary.root, "private-exports"), "${artifact.id}.receipt").isFile)
                    closed = true
                }
            }
        }
        assertTrue(closed)
        assertNull(artifacts.find(artifact.id))
    }

    @Test fun canceledPreparationClosesAndRemovesItsPrivatePartialWithoutPublishingAReceipt(): Unit = runBlocking {
        val artifacts = store()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                artifacts.prepare(false) {
                    it.write(ByteArray(128 * 1024))
                    throw CancellationException("leaving during preparation")
                }
            }
        }
        assertNull(artifacts.latest())
        assertTrue(File(temporary.root, "private-exports").listFiles().orEmpty().isEmpty())
    }

    @Test fun sameLengthCorruptionIsRejectedBeforeTheDestinationIsOpened(): Unit = runBlocking {
        val artifacts = store()
        val artifact = artifacts.prepare(false) { it.write("{\"complete\":true}".toByteArray()) }
        val content = File(File(temporary.root, "private-exports"), "${artifact.id}.json")
        RandomAccessFile(content, "rw").use { it.writeByte('X'.code) }
        assertNotNull(artifacts.latest())
        var destinationOpened = false
        assertThrows(IOException::class.java) {
            runBlocking {
                artifacts.saveTo(artifact) {
                    destinationOpened = true
                    object : OutputStream() {
                        override fun write(value: Int) = Unit
                    }
                }
            }
        }
        assertFalse(destinationOpened)
        assertNotNull(artifacts.find(artifact.id))
    }

    @Test fun staleCleanupRemovesOnlyOwnedOrphansAndKeepsEveryCompleteReceipt(): Unit = runBlocking {
        val day = 24 * 60 * 60 * 1000L
        val folder = File(temporary.root, "cleanup")
        val artifacts = BenchmarkExportArtifactStore(directory = { folder }, now = { 10 * day })
        val complete = artifacts.prepare(false) { it.write("{\"complete\":true}".toByteArray()) }
        folder.listFiles().orEmpty().forEach { assertTrue(it.setLastModified(2 * day)) }
        val abandonedId = UUID.randomUUID().toString()
        val orphanFiles = listOf("$abandonedId.partial", "$abandonedId.receipt.partial", "${UUID.randomUUID()}.json")
            .map { name ->
                File(folder, name).apply {
                    writeText("orphan")
                    assertTrue(setLastModified(2 * day))
                }
            }
        val unrelated = File(folder, "notes.partial").apply {
            writeText("leave alone")
            assertTrue(setLastModified(2 * day))
        }
        val fresh = File(folder, "${UUID.randomUUID()}.partial").apply {
            writeText("recent work")
            assertTrue(setLastModified(9 * day))
        }
        val unknownId = UUID.randomUUID().toString()
        val unknownReceipt = File(folder, "$unknownId.receipt").apply {
            writeText("unreadable-or-newer-receipt")
            assertTrue(setLastModified(2 * day))
        }
        val unknownPayload = File(folder, "$unknownId.json").apply {
            writeText("retained evidence")
            assertTrue(setLastModified(2 * day))
        }
        assertTrue(artifacts.cleanupStaleOrphans() >= orphanFiles.size)
        assertTrue(orphanFiles.none { it.exists() })
        assertTrue(unrelated.isFile)
        assertTrue(fresh.isFile)
        assertTrue(unknownReceipt.isFile)
        assertTrue(unknownPayload.isFile)
        assertEquals(complete, artifacts.find(complete.id))
        artifacts.verify(complete)
    }

    @Test fun staleCleanupNeverRemovesAnActiveStagingFileAcrossStoreInstances(): Unit = runBlocking {
        val day = 24 * 60 * 60 * 1000L
        val folder = File(temporary.root, "active")
        val artifacts = BenchmarkExportArtifactStore(directory = { folder }, now = { 10 * day })
        val cleanup = BenchmarkExportArtifactStore(directory = { folder }, now = { 10 * day })
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staging = async(Dispatchers.Default) {
            artifacts.prepare(false) { output ->
                output.write("{\"complete\":".toByteArray())
                output.flush()
                folder.listFiles().orEmpty().forEach { assertTrue(it.setLastModified(2 * day)) }
                started.complete(Unit)
                release.await()
                output.write("true}".toByteArray())
            }
        }
        started.await()
        try {
            assertEquals(0, cleanup.cleanupStaleOrphans())
            assertTrue(folder.listFiles().orEmpty().any { it.name.endsWith(".partial") })
            assertTrue(folder.listFiles().orEmpty().any { it.name.endsWith(".lock") })
        } finally {
            release.complete(Unit)
        }
        val complete = staging.await()
        assertEquals(complete, cleanup.find(complete.id))
        cleanup.verify(complete)
    }

    @Test fun cancellationInterruptsSynchronousLargeExportAtTheNextBuffer(): Unit = runBlocking {
        val artifacts = store()
        var chunks = 0
        val preparation = async {
            artifacts.prepare(false) { output ->
                kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                repeat(1000) {
                    output.write(ByteArray(64 * 1024))
                    chunks++
                }
            }
        }
        try {
            preparation.await()
            throw AssertionError("Canceled preparation unexpectedly completed")
        } catch (_: CancellationException) {
            assertTrue(chunks <= 1)
        }
        assertNull(artifacts.latest())
        assertTrue(File(temporary.root, "private-exports").listFiles().orEmpty().isEmpty())
    }
}
