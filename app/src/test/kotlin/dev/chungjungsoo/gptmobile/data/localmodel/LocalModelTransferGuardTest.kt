package dev.chungjungsoo.gptmobile.data.localmodel

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelTransferGuardTest {
    @Test
    fun replacedGenerationCannotPublishOrWrite() = runBlocking {
        val root = Files.createTempDirectory("model-transfer-test").toFile()
        try {
            val first = LocalModelTransferGuard.mutex("model").withLock { LocalModelTransferGuard.begin(root, "model") }
            var writes = 0
            LocalModelTransferGuard.withCurrent(root, "model", first) { writes++ }
            val second = LocalModelTransferGuard.mutex("model").withLock { LocalModelTransferGuard.begin(root, "model") }
            var cancelled = false
            try {
                LocalModelTransferGuard.withCurrent(root, "model", first) { writes++ }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals(1, writes)
            LocalModelTransferGuard.withCurrent(root, "model", second) { writes++ }
            assertEquals(2, writes)
            assertEquals(second, root.resolve("local-model-transfers/model").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
