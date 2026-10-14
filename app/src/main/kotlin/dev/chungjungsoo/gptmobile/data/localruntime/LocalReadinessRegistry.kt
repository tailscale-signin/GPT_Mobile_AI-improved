package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalArtifactIncompatibleException(cause: Throwable? = null) :
    IllegalStateException(
        "ENGINE_INITIALIZATION: This model export could not initialize on the selected backend (Input tensor not found). In Local AI, check the model edition and backend; NPU exports need their matching device/runtime. Import a compatible export, then run Verify. This failed configuration will not be retried automatically.",
        cause
    )

/** Only deterministic artifact errors are retained. Resource pressure and cancellation stay retryable. */
internal class LocalReadinessRegistry(private val directory: File? = null) {
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val lock = Mutex()

    suspend fun <T> qualify(key: String, load: suspend () -> T): T = lock.withLock {
        val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(key)
        val durable = directory?.let { File(it, digest) }
        if (key in blocked || withContext(Dispatchers.IO) { durable?.isFile == true }) throw LocalArtifactIncompatibleException()
        try {
            load()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (deterministicFailure(error)) {
                blocked += key
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    durable?.let { target ->
                        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
                        val temporary = File(target.parentFile, "$digest.tmp")
                        temporary.outputStream().use { output ->
                            output.write(digest.toByteArray(Charsets.UTF_8))
                            output.fd.sync()
                        }
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                throw LocalArtifactIncompatibleException(error)
            }
            throw error
        }
    }

    /** Called only after the exact user-requested verification reset was acknowledged. */
    suspend fun reset(key: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            directory?.let { File(it, dev.chungjungsoo.gptmobile.data.research.researchHash(key)) }?.let {
                check(!it.exists() || it.delete()) { "Cannot reset this artifact's verification state" }
            }
        }
        blocked.remove(key)
    }

    companion object {
        fun deterministicFailure(error: Throwable): Boolean = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }
            .take(8).any { it.message.orEmpty().contains("Input tensor not found", true) || it is LocalArtifactIncompatibleException }
    }
}
