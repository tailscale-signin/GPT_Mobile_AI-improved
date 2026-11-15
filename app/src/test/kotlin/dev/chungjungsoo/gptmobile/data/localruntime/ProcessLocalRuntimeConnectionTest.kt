package dev.chungjungsoo.gptmobile.data.localruntime

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Messenger
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.mockk
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class ProcessLocalRuntimeConnectionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val runtimes = mutableListOf<ProcessLocalRuntime>()

    @After
    fun stopFixtureThreads() {
        runtimes.forEach { runtime ->
            ReflectionHelpers.callInstanceMethod<Unit>(runtime, "unbind")
            ReflectionHelpers.getField<CoroutineScope>(runtime, "scope").cancel()
            val thread = ReflectionHelpers.getField<HandlerThread>(runtime, "thread")
            thread.quitSafely()
            thread.join(5_000)
            assertFalse("The fixture client thread must settle", thread.isAlive)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun bindingDiedDuringActualCommandRetainsItsCauseAndSettlesTheRequest() = runBlocking {
        val context = bindingContext()
        val runtime = runtime(context)
        val attempt = async { runCatching { runtime.inspectModel("PRIVATE_FIXTURE_MODEL") }.exceptionOrNull() }
        val connection = withTimeout(5_000) { context.bindings.receive() }
        val pendingBinding = ReflectionHelpers.getField<CompletableDeferred<Messenger>>(runtime, "binding")

        connection.onBindingDied(ComponentName("fixture", "InferenceService"))

        val failure = withTimeout(5_000) { attempt.await() }
        assertTrue(failure is LocalRuntimeConnectionException)
        val original = requireNotNull(pendingBinding.getCompletionExceptionOrNull())
        assertTrue(generateSequence(failure) { it.cause }.any { it === original })
        assertEquals("Inference binding expired", original.message)
        assertTrue(original.stackTrace.any { it.methodName == "onBindingDied" })
        assertEquals(1, context.bindCalls.get())
        assertEquals(1, context.unbindCalls.get())
        assertFalse(runtime.isBusy)
        assertNull(runtime.latestInspectionReceipt)
        assertNull(runtime.latestEngineLoadReceipt)
        assertNull(runtime.latestExecutionReceipt)
    }

    @Test
    fun actualBindFailuresAreWrappedOnlyAfterUnbinding() = runBlocking {
        val original = SecurityException("PRIVATE_BIND_DENIED")
        for (throwFromBind in listOf(false, true)) {
            val context = bindingContext().apply {
                bindAction = { if (throwFromBind) throw original else false }
            }
            val failure = runCatching { connect(runtime(context)) }.exceptionOrNull()
            assertTrue(failure is LocalRuntimeConnectionException)
            assertEquals(1, context.bindCalls.get())
            assertEquals(1, context.unbindCalls.get())
            if (throwFromBind) {
                assertSame(original, failure?.cause)
            } else {
                assertTrue(failure?.cause is IllegalStateException)
                assertEquals("Could not bind inference service", failure?.cause?.message)
            }
        }
    }

    @Test
    fun cancellationWhileAwaitingTheActualBindingIsNotWrapped() = runBlocking {
        val context = bindingContext()
        val runtime = runtime(context)
        val observed = CompletableDeferred<Throwable>()
        val attempt = launch {
            try {
                connect(runtime)
            } catch (error: Throwable) {
                observed.complete(error)
            }
        }
        withTimeout(5_000) { context.bindings.receive() }
        val cancellation = CancellationException("PRIVATE_CANCELLED_BIND")

        attempt.cancel(cancellation)
        withTimeout(5_000) { attempt.join() }

        val failure = observed.await()
        assertTrue(failure is CancellationException)
        assertTrue(generateSequence(failure) { it.cause }.any { it === cancellation })
        assertFalse(generateSequence(failure) { it.cause }.any { it is LocalRuntimeConnectionException })
        assertEquals(1, context.unbindCalls.get())
    }

    @Test
    fun timeoutCancellationAndFatalBindingErrorsRemainUnwrapped() = runBlocking {
        val timeoutContext = bindingContext()
        val timeout = runCatching { withTimeout(500) { connect(runtime(timeoutContext)) } }.exceptionOrNull()
        assertTrue(timeout is TimeoutCancellationException)
        assertEquals(1, timeoutContext.bindCalls.get())
        assertEquals(1, timeoutContext.unbindCalls.get())
        val fatal = LinkageError("PRIVATE_FATAL_BIND")
        val fatalContext = bindingContext().apply { bindAction = { throw fatal } }
        assertSame(fatal, runCatching { connect(runtime(fatalContext)) }.exceptionOrNull())
        assertEquals(1, fatalContext.unbindCalls.get())
    }

    @Test
    fun unresolvedAdmissionFailsBeforeTheBindingTryAndKeepsItsType() = runBlocking {
        val context = bindingContext()
        val runtime = runtime(context)
        ReflectionHelpers.setField(runtime, "cleanupQuarantine", "PRIVATE_UNSETTLED_NATIVE_OPERATION")
        ReflectionHelpers.setField(runtime, "everBound", true)
        ReflectionHelpers.setField(runtime, "nativeReleaseAcknowledged", false)

        val failure = runCatching { connect(runtime) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(failure is LocalRuntimeConnectionException)
        assertTrue(failure?.message.orEmpty().startsWith("NATIVE_SESSION_QUARANTINED:"))
        assertEquals(0, context.bindCalls.get())
        assertEquals(0, context.unbindCalls.get())
    }

    @Test
    fun successfulBindingAndExistingMessengerReuseDoNotCreateFailureEvidence() = runBlocking {
        val worker = Messenger(Handler(Looper.getMainLooper()))
        val context = bindingContext().apply {
            bindAction = { connection ->
                connection.onServiceConnected(ComponentName("fixture", "InferenceService"), worker.binder)
                true
            }
        }
        val runtime = runtime(context)

        val connected = connect(runtime)
        val reused = connect(runtime)

        assertSame(worker.binder, connected.binder)
        assertSame(connected, reused)
        assertEquals(1, context.bindCalls.get())
        assertEquals(0, context.unbindCalls.get())
    }

    private fun runtime(context: Context): ProcessLocalRuntime =
        ProcessLocalRuntime(context, mockk<SettingRepository>()).also(runtimes::add)

    private fun bindingContext() = BindingContext(RuntimeEnvironment.getApplication(), temporaryFolder.newFolder())

    /** Calls the real private suspend method, avoiding unrelated command cancellation deadlines in opposite controls. */
    private suspend fun connect(runtime: ProcessLocalRuntime): Messenger = suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = ProcessLocalRuntime::class.java.getDeclaredMethod("connect", Continuation::class.java).apply { isAccessible = true }
        try {
            method.invoke(runtime, continuation)
        } catch (error: InvocationTargetException) {
            throw requireNotNull(error.cause)
        }
    }

    private class BindingContext(base: Context, private val storage: File) : ContextWrapper(base) {
        val bindCalls = AtomicInteger()
        val unbindCalls = AtomicInteger()
        val bindings = Channel<ServiceConnection>(Channel.UNLIMITED)
        var bindAction: (ServiceConnection) -> Boolean = { true }

        override fun getNoBackupFilesDir(): File = storage

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            bindCalls.incrementAndGet()
            check(bindings.trySend(connection).isSuccess)
            return bindAction(connection)
        }

        override fun unbindService(connection: ServiceConnection) {
            unbindCalls.incrementAndGet()
        }
    }
}
