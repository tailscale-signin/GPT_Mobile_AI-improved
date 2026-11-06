package dev.chungjungsoo.gptmobile.data.diagnostics

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** Clearing diagnostics or deleting a chat invalidates in-flight capture as well as saved stages. */
internal object DiagnosticCaptureStorage {
    private val epoch = AtomicLong()
    private val chatEpochs = ConcurrentHashMap<Int, AtomicLong>()

    fun directory(context: Context, chatId: Int): File = File(File(context.noBackupFilesDir, "diagnostic-captures"), "chat-$chatId")

    fun guard(chatId: Int): () -> Unit {
        val global = epoch.get()
        val local = chatEpochs.computeIfAbsent(chatId) { AtomicLong() }
        val revision = local.get()
        return {
            if (global != epoch.get() || revision != local.get()) throw CancellationException("Diagnostic capture was cleared or its conversation was deleted.")
        }
    }

    fun clear(context: Context) {
        epoch.incrementAndGet()
        File(context.noBackupFilesDir, "diagnostic-captures").deleteRecursively()
    }

    fun deleteChat(context: Context, chatId: Int) {
        chatEpochs.computeIfAbsent(chatId) { AtomicLong() }.incrementAndGet()
        directory(context, chatId).deleteRecursively()
    }
}
