package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSourceStorageTest {
    @Test fun sourceCompressionRemainsEncryptedAndRoundTripsAcrossRestart() = runBlocking {
        val dir = Files.createTempDirectory("source-encryption-test").toFile()
        try {
            val context = mockk<Context>()
            every { context.noBackupFilesDir } returns dir
            val keys = mutableMapOf<String, ByteArray>()
            val vault = object : SecretVault {
                override suspend fun put(secretRef: String, secret: ByteArray) {
                    keys[secretRef] = secret.copyOf()
                }
                override suspend fun read(secretRef: String): ByteArray? = keys[secretRef]?.copyOf()
                override suspend fun delete(secretRef: String) {
                    keys.remove(secretRef)
                }
            }
            val body = buildJsonObject { put("content", "PRIVATE_SOURCE_CANARY\n".repeat(5000)) }
            GitHubRepositoryStateStore(context, vault).write("account:repo", "blob:large", body)
            val file = File(dir, "github-repository-state/source-blobs").listFiles()!!.single()
            assertTrue(file.length() < body.toString().toByteArray().size / 4)
            assertFalse(file.readBytes().decodeToString().contains("PRIVATE_SOURCE_CANARY"))
            assertEquals(body, GitHubRepositoryStateStore(context, vault).read("account:repo", "blob:large"))
            assertNull(GitHubRepositoryStateStore(context, vault).read("another-account:repo", "blob:large"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun sourceIndexSurvivesOrdinaryCachePressureAndRestart() = runBlocking {
        val dir = Files.createTempDirectory("source-cache-test").toFile()
        try {
            val store = GitHubRepositoryStateStore(dir)
            val body = buildJsonObject { put("source", "retained") }
            repeat(1600) { store.write("account:owner/repo", "blob:$it", body) }
            store.write("account:owner/repo", "phone-graph:shard", body)
            repeat(1600) { store.write("other", "ordinary:$it", body) }
            val restored = GitHubRepositoryStateStore(dir)
            assertNotNull(restored.read("account:owner/repo", "blob:0"))
            assertNotNull(restored.read("account:owner/repo", "blob:1599"))
            assertNotNull(restored.read("account:owner/repo", "phone-graph:shard"))
            assertNull(restored.read("other-account:owner/repo", "blob:0"))
            assertEquals(1600, File(dir, "source-blobs").listFiles()!!.count { it.extension == "record" })
            assertEquals(JsonPrimitive(true), restored.sourceStorageStatus()["execution_receipts_separate"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun legacySourceBodyMigratesLazilyWithSameIdentity() = runBlocking {
        val dir = Files.createTempDirectory("source-migration-test").toFile()
        try {
            val identity = "account:repo:blob:old"
            val file = File(dir, GitHubRepositoryStateStore.digest(identity) + ".record")
            file.writeText("{\"content\":\"retained source\"}")
            val store = GitHubRepositoryStateStore(dir)
            assertTrue(store.containsRetainedBody("account:repo", "blob:old"))
            assertEquals(JsonPrimitive("retained source"), store.read("account:repo", "blob:old")!!["content"])
            assertFalse(file.exists())
            assertTrue(File(File(dir, "source-blobs"), file.name).isFile)
            assertTrue(GitHubRepositoryStateStore(dir).containsRetainedBody("account:repo", "blob:old"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
