package dev.chungjungsoo.gptmobile.data.chat

import dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Navigation must not wait for Room, archive compression, or native cancellation. */
@Singleton
class ConversationExitWork internal constructor(private val scope: CoroutineScope) {
    @Inject constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val pending = ConcurrentHashMap<Int, Job>()

    @Synchronized
    fun enqueue(chatId: Int, work: suspend () -> Unit): Job {
        val previous = pending[chatId]
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.join()
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogRecorder.record("Conversation", "EXIT_SAVE_FAILED · chat=$chatId · error=${error.javaClass.simpleName}", "E")
            }
        }
        pending[chatId] = job
        job.invokeOnCompletion { pending.remove(chatId, job) }
        job.start()
        return job
    }

    /** Reopening waits in a coroutine before restoring a draft, never on the UI thread. */
    suspend fun awaitPending(chatId: Int) {
        pending[chatId]?.join()
    }
}
