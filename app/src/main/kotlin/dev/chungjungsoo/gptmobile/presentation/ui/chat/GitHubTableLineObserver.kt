package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextLayoutResult

internal data class GitHubTableLayoutSnapshot(val source: String, val cells: List<TextLayoutResult>)

/** Row source owns identity even when a different cell becomes tallest after reflow. */
@Composable
internal fun rememberGitHubTableLayout(key: Any, sourceOffset: Int, source: String, firstAppearanceAddsLines: Boolean): (List<TextLayoutResult>) -> Unit {
    val scope = LocalGitHubLineScope.current
    return remember(scope, key, sourceOffset, source, firstAppearanceAddsLines) {
        { cells ->
            if (scope != null && cells.isNotEmpty()) {
                val layoutKey = scope.document to key
                scope.owner.textLayouts.remove(layoutKey)
                val previous = scope.owner.tableLayouts.put(layoutKey, GitHubTableLayoutSnapshot(source, cells))
                val geometry = cells.map { gitHubLineGeometry(it.layoutInput) }
                val previousLines = if (previous != null && previous.source != source && previous.cells.map { gitHubLineGeometry(it.layoutInput) } != geometry) {
                    previous.cells.mapIndexed { index, result ->
                        previousGitHubLinesAtGeometry(result.layoutInput, (cells.getOrNull(index) ?: cells.last()).layoutInput, scope.measurer)
                    }.maxOrNull()
                } else {
                    null
                }
                scope.owner.ledger.observe(
                    key = layoutKey,
                    document = scope.document,
                    sourceOffset = sourceOffset,
                    text = source,
                    lines = cells.maxOf { it.lineCount },
                    geometry = geometry,
                    previousLinesAtGeometry = previousLines,
                    firstAppearanceAddsLines = firstAppearanceAddsLines
                )
                scope.owner.changed.trySend(Unit)
            }
        }
    }
}
