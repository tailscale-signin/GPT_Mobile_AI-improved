package dev.chungjungsoo.gptmobile.data.localruntime

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reserve idle native resources before unlinking any mapped model assets. */
internal suspend fun LocalRuntime.removeModelFilesIfIdle(directories: List<File>, deleteFiles: suspend () -> Unit) = withContext(Dispatchers.IO) {
    val roots = directories.map { it.canonicalPath + File.separator }
    val removed = tryRunExclusive {
        // unloadIfIdle tries the same engine lock; this reservation already owns it.
        val loaded = loadedEngineSpec()?.modelPath?.let { File(it).canonicalPath }
        if (loaded != null && roots.any(loaded::startsWith)) unloadEngine()
        deleteFiles()
        true
    }
    check(removed == true) { "Finish the active local response or benchmark before deleting model files." }
}
