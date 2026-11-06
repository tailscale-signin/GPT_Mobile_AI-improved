package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.ContextWrapper
import dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
class DiagnosticStartupThreadTest {
    @Test fun runtimeAndProviderRegistrationDoNotResolveAndroidDirectoriesOnMain() = runBlocking {
        val caller = Thread.currentThread()
        val lookups = AtomicInteger()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File {
                assertNotEquals(caller, Thread.currentThread())
                lookups.incrementAndGet()
                return super.getNoBackupFilesDir()
            }
        }
        LocalRuntimeIncidentHistory.initialize(context)
        FreeProviderDiagnostics.initialize { File(context.noBackupFilesDir, "thread-provider-diagnostics.json") }
        assertEquals(0, lookups.get())
        withContext(Dispatchers.IO) {
            assertTrue(LocalRuntimeIncidentHistory.capture().records.isEmpty())
            assertTrue(FreeProviderDiagnostics.snapshot().isEmpty())
            LocalRuntimeIncidentHistory.capture()
            FreeProviderDiagnostics.snapshot()
        }
        assertEquals(2, lookups.get())
    }
}
