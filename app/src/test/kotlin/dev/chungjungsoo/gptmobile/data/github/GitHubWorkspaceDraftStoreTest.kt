package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitHubWorkspaceDraftStoreTest {
    @Test fun directoryResolutionIsDeferredToIoAndEncryptedDraftsRoundTrip() = runBlocking {
        val callerThread = Thread.currentThread()
        val folder = Files.createTempDirectory("workspace-draft-test").toFile()
        var accessed = false
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): java.io.File {
                assertNotSame(callerThread, Thread.currentThread())
                accessed = true
                return folder
            }
        }
        val secrets = mutableMapOf<String, ByteArray>()
        val vault = object : SecretVault {
            override suspend fun put(secretRef: String, secret: ByteArray) { secrets[secretRef] = secret.copyOf() }
            override suspend fun read(secretRef: String): ByteArray? = secrets[secretRef]?.copyOf()
            override suspend fun delete(secretRef: String) { secrets.remove(secretRef) }
        }
        try {
            val store = GitHubWorkspaceDraftStore(context, vault)
            assertFalse(accessed)
            store.write("connection-A", "Saved private edits")
            assertTrue(accessed)
            assertEquals("Saved private edits", store.read("connection-A"))
            assertEquals(null, store.read("connection-B"))
            val encrypted = folder.resolve("github-workspace-drafts").listFiles()!!.single().readBytes()
            assertFalse(encrypted.toString(Charsets.UTF_8).contains("Saved private edits"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun constructionDoesNotTouchTheContextFilesystem() {
        GitHubWorkspaceDraftStore(mockk(), mockk())
    }
}
