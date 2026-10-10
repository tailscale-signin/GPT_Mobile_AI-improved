package dev.chungjungsoo.gptmobile.data.localmodel

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable work identity plus one short lock for activation, cancellation, writes and deletion. */
internal object LocalModelTransferGuard {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun mutex(id: String): Mutex = locks.getOrPut(id) { Mutex() }

    // Caller holds mutex(id). Stored outside models so deleting artifacts cannot resurrect old work.
    fun begin(root: File, id: String): String {
        LocalModelDownloadPaths.requireValidPathSegments(id)
        val directory = File(root, "local-model-transfers")
        check(directory.mkdirs() || directory.isDirectory)
        val generation = UUID.randomUUID().toString()
        val staging = File(directory, "$id.tmp")
        staging.outputStream().use {
            it.write(generation.toByteArray())
            it.fd.sync()
        }
        Files.move(staging.toPath(), File(directory, id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return generation
    }

    suspend fun <T> withCurrent(root: File, id: String, generation: String, action: suspend () -> T): T = mutex(id).withLock {
        currentCoroutineContext().ensureActive()
        val file = File(File(root, "local-model-transfers"), id)
        if (generation.isBlank() || !file.isFile || file.readText() != generation) {
            throw CancellationException("Obsolete local model download")
        }
        action()
    }
}
