package dev.chungjungsoo.gptmobile.data.agent

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A content-free initialization marker survives deletion of inspectable workspace records. */
internal class ToolCallBudgetFence private constructor(private val file: File) {
    private val lock = locks.computeIfAbsent(file.absolutePath) { Mutex() }
    fun exists(): Boolean = file.exists() || file.absolutePath in deleted

    suspend fun mark() = withContext(Dispatchers.IO) {
        lock.withLock {
            check(file.absolutePath !in deleted) { "The conversation's tool allowance was deleted; no stale worker may recreate it." }
            if (!file.exists()) {
                val directory = checkNotNull(file.parentFile)
                check(directory.isDirectory || directory.mkdirs()) { "Cannot retain the accepted tool allowance." }
                // This marker is written before the protected counter. If either write
                // fails, a later worker sees an initialized but unreadable allowance.
                FileOutputStream(file).use { output ->
                    output.write(1)
                    output.fd.sync()
                }
                java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
            }
        }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val deleted = ConcurrentHashMap.newKeySet<String>()
        fun forTurn(directory: File, chatId: Int, binding: String): ToolCallBudgetFence {
            require(chatId > 0)
            val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(binding)
            return ToolCallBudgetFence(File(directory, "tool-budget-fences/$chatId/$digest"))
        }

        suspend fun deleteChat(directory: File, chatId: Int) = withContext(Dispatchers.IO) {
            val folder = File(directory, "tool-budget-fences/$chatId")
            val files = (folder.listFiles().orEmpty().toList() + locks.keys.filter { it.startsWith(folder.absolutePath + File.separator) }.map(::File))
                .distinctBy { it.absolutePath }
            files.forEach { file ->
                locks.computeIfAbsent(file.absolutePath) { Mutex() }.withLock {
                    deleted += file.absolutePath
                    check(!file.exists() || file.delete()) { "Could not delete the conversation's tool allowance marker." }
                }
            }
            if (folder.listFiles().isNullOrEmpty()) folder.delete()
        }
    }
}
