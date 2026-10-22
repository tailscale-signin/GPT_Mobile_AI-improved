package dev.chungjungsoo.gptmobile.data.delegation

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WriterLoadReadinessTest {
    @Test fun anonymousProviderDenialExpiresAcrossRestartsWithoutChangingKeyedProviderPolicy() {
        val directory = Files.createTempDirectory("free-denial-test").toFile()
        try {
            var now = 100000L
            val profile = PlatformV2(name = "Horde", compatibleType = ClientType.FREE)
            KnownWriterFailures(directory) { now }.observe(profile, "AI Horde: HTTP 403")
            val restored = KnownWriterFailures(directory) { now }
            assertNotNull(restored.blocked(profile))
            now += 59999
            assertNotNull(restored.blocked(profile))
            now++
            assertNull(restored.blocked(profile))
            assertEquals(0, directory.listFiles()!!.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun modelLoadCooldownIsScopedExpiresAndCanBeExplicitlyRechecked() {
        val directory = Files.createTempDirectory("writer-load-test").toFile()
        try {
            var now = 100000L
            val writer = PlatformV2(name = "local server", model = "model-a", compatibleType = ClientType.LLAMA)
            val store = KnownWriterFailures(directory) { now }
            store.observe(writer, "HTTP 500: model failed to load")
            assertEquals("model_load_failed", store.blocked(writer)?.code)
            assertNull(store.blocked(writer.copy(model = "model-b")))
            assertNull(store.blocked(writer.copy(token = "changed")))
            now += 30000
            assertNull(store.blocked(writer))
            store.observe(writer, "HTTP 500: model failed to load")
            store.recheck(writer)
            assertNull(store.blocked(writer))
            store.observe(writer, "HTTP 403: forbidden")
            now += 3600000
            assertNotNull(store.blocked(writer))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun genericServerErrorIsNotMisclassifiedAsModelLoadFailure() {
        val directory = Files.createTempDirectory("writer-transient-test").toFile()
        try {
            val writer = PlatformV2(name = "server", compatibleType = ClientType.LLAMA)
            val store = KnownWriterFailures(directory)
            store.observe(writer, "HTTP 500: transient upstream failure")
            assertNull(store.blocked(writer))
        } finally {
            directory.deleteRecursively()
        }
    }
}
