package dev.chungjungsoo.gptmobile.data.github

import android.app.Application
import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubStateStoreInitializationTest {
    @Test fun constructionDefersDirectoryLookupToIoAndDeletionUsesTheSameBoundary() = runBlocking {
        val directory = Files.createTempDirectory("github-state-init").toFile()
        try {
            val caller = Thread.currentThread()
            var lookup: Thread? = null
            val context = object : ContextWrapper(null) {
                override fun getNoBackupFilesDir(): File {
                    lookup = Thread.currentThread()
                    assertNotEquals(caller, lookup)
                    return directory
                }
            }
            val secrets = mutableMapOf<String, ByteArray>()
            val vault = object : SecretVault {
                override suspend fun put(secretRef: String, secret: ByteArray) {
                    secrets[secretRef] = secret.copyOf()
                }
                override suspend fun read(secretRef: String): ByteArray? = secrets[secretRef]?.copyOf()
                override suspend fun delete(secretRef: String) {
                    secrets.remove(secretRef)
                }
            }
            val state = GitHubRepositoryStateStore(context, vault)
            assertNull(lookup)
            val observed = buildJsonObject { put("sha", "a".repeat(40)) }
            state.write("scope", "cache", observed)
            assertEquals(observed, state.read("scope", "cache"))
            state.remove("scope", "cache")
            assertNull(state.read("scope", "cache"))
            val criticalKey = "execution:" + "b".repeat(64)
            val critical = buildJsonObject {
                put("status", "COMPLETED")
                put("result", observed)
            }
            state.write("scope", criticalKey, critical)
            val filename = GitHubRepositoryStateStore.digest("scope:$criticalKey") + ".record"
            val protected = directory.resolve("github-repository-state").resolve("execution-receipts").resolve(filename)
            val legacy = directory.resolve("github-repository-state").resolve(filename)
            org.junit.Assert.assertTrue(protected.renameTo(legacy))
            assertEquals("Encrypted legacy bytes keep the same identity after migration", critical, GitHubRepositoryStateStore(context, vault).read("scope", criticalKey))
            lookup = null
            val unopened = GitHubRepositoryStateStore(context, vault)
            assertNull(lookup)
            unopened.remove("scope", "missing")
            assertNotEquals(caller, lookup)
        } finally {
            directory.deleteRecursively()
        }
    }
}
