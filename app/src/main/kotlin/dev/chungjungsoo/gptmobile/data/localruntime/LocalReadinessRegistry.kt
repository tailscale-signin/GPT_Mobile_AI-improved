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

class LocalNativeQuarantinedException :
    IllegalStateException(
        "NATIVE_ARTIFACT_QUARANTINED: This artifact/runtime/backend was interrupted inside native code. In Local AI choose Verify load for this model/backend, or explicitly select a compatible backend/model. No native work was dispatched."
    )

/** Only deterministic errors and explicit native interruption denials are retained. */
internal class LocalReadinessRegistry(private val directory: File? = null) {
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val lock = Mutex()
    private val nativeLock = Mutex()

    private val nativeBlocked = ConcurrentHashMap.newKeySet<String>()
    private val incompatibleRoutes = ConcurrentHashMap.newKeySet<String>()

    /** Parent-process read: never touches native pointers or clears a worker's protective journal. */
    suspend fun assertNativeAllowed(key: String) = nativeLock.withLock {
        val durable = directory?.let { File(it, dev.chungjungsoo.gptmobile.data.research.researchHash(key)) }
        val recorded = withContext(Dispatchers.IO) { durable?.takeIf(File::isFile)?.readText()?.lineSequence()?.firstOrNull() }
        if (key in nativeBlocked || recorded == "NATIVE") {
            nativeBlocked += key
            throw LocalNativeQuarantinedException()
        }
        if (key in incompatibleRoutes || recorded == "INCOMPATIBLE") {
            incompatibleRoutes += key
            throw LocalArtifactIncompatibleException()
        }
    }

    suspend fun rememberNativeInterruption(key: String) = nativeLock.withLock {
        nativeBlocked += key
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            directory?.let { root ->
                check(root.mkdirs() || root.isDirectory)
                val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(key)
                val temporary = File(root, "$digest.tmp")
                temporary.outputStream().use { output ->
                    output.write("NATIVE\n$digest".toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                Files.move(temporary.toPath(), File(root, digest).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** Reject repeated incompatible exports across CPU/GPU tuning and context tweaks. */
    suspend fun rememberIncompatibleRoute(key: String) = nativeLock.withLock {
        incompatibleRoutes += key
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            directory?.let { root ->
                check(root.mkdirs() || root.isDirectory)
                val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(key)
                val temporary = File(root, "$digest.tmp")
                temporary.outputStream().use { output ->
                    output.write("INCOMPATIBLE\\n$digest".replace("\\\\n", "\\n").toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                Files.move(temporary.toPath(), File(root, digest).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

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
        nativeLock.withLock {
            withContext(Dispatchers.IO) {
                directory?.let { File(it, dev.chungjungsoo.gptmobile.data.research.researchHash(key)) }?.let {
                    check(!it.exists() || it.delete()) { "Cannot reset this artifact's verification state" }
                }
            }
            blocked.remove(key)
            nativeBlocked.remove(key)
            incompatibleRoutes.remove(key)
        }
    }

    companion object {
        fun deterministicFailure(error: Throwable): Boolean = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }
            .take(8).any { it.message.orEmpty().contains("Input tensor not found", true) || it is LocalArtifactIncompatibleException }
    }
}
