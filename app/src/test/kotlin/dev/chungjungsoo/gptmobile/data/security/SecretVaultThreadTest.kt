package dev.chungjungsoo.gptmobile.data.security

import android.app.Application
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SecretVaultThreadTest {
    @Test fun constructorDefersFilesystemLookupUntilIoOperation() = runBlocking {
        val caller = Thread.currentThread()
        val lookups = AtomicInteger()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File {
                assertNotEquals(caller, Thread.currentThread())
                lookups.incrementAndGet()
                return super.getNoBackupFilesDir()
            }
        }
        val vault = AndroidSecretVault(context, "thread-test-key", "thread-test-vault")
        assertEquals(0, lookups.get())
        assertTrue(vault.references().isEmpty())
        assertTrue(vault.references().isEmpty())
        assertEquals(1, lookups.get())
    }
}
