package dev.chungjungsoo.gptmobile.presentation.ui.setting

import java.io.OutputStream
import kotlinx.coroutines.CancellationException

/** Missing/broken document pickers report an actionable error instead of escaping a click. */
internal fun launchBenchmarkExportDestination(
    fileName: String,
    launch: (String) -> Unit,
    onFailure: (Exception) -> Unit
): Boolean = try {
    launch(fileName)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    onFailure(failure)
    false
}

/** Retry authority follows the retained artifact, never a stale screen detail selector. */
internal suspend fun saveBenchmarkExportArtifact(
    artifacts: BenchmarkExportArtifactStore,
    artifact: BenchmarkExportArtifact,
    currentDebugMode: suspend () -> Boolean,
    openDestination: () -> OutputStream?
) {
    check(!artifact.detailed || currentDebugMode()) { "Enable Debug Mode to save the retained diagnostic export." }
    artifacts.saveTo(artifact, openDestination)
}
