package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextLayoutInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.channels.Channel

/** Plain state: layout reports wake the follower without recomposing the transcript. */
internal class GitHubRenderedLines {
    val ledger = GitHubRenderedLineLedger()
    val changed = Channel<Unit>(Channel.CONFLATED)
    val queue = GitHubLinePacingQueue()
    val textLayouts = mutableMapOf<Any, TextLayoutResult>()
    val tableLayouts = mutableMapOf<Any, GitHubTableLayoutSnapshot>()

    fun baseline() {
        ledger.baseline()
        queue.clear()
    }
}

internal data class GitHubLineScope(val owner: GitHubRenderedLines, val document: Any, val measurer: TextMeasurer)
internal val LocalGitHubLineScope = staticCompositionLocalOf<GitHubLineScope?> { null }

@Composable
internal fun GitHubLineScope(owner: GitHubRenderedLines?, identity: Any, content: @Composable () -> Unit) {
    val measurer = if (owner != null) rememberTextMeasurer(cacheSize = 0) else null
    val scope = remember(owner, identity, measurer) { owner?.let { GitHubLineScope(it, identity, requireNotNull(measurer)) } }
    CompositionLocalProvider(LocalGitHubLineScope provides scope, content = content)
}

@Composable
internal fun GitHubLineDocument(identity: Any, source: String, content: @Composable () -> Unit) {
    val parent = LocalGitHubLineScope.current
    val scope = remember(parent, identity) { parent?.let { GitHubLineScope(it.owner, it.document to identity, it.measurer) } }
    scope?.owner?.ledger?.document(scope.document, source.length)
    CompositionLocalProvider(LocalGitHubLineScope provides scope, content = content)
}

@Composable
internal fun rememberGitHubTextLayout(key: Any, sourceOffset: Int = 0, updatesAddLines: Boolean = true, firstAppearanceAddsLines: Boolean = true): (TextLayoutResult) -> Unit {
    val scope = LocalGitHubLineScope.current
    return remember(scope, key, sourceOffset, updatesAddLines, firstAppearanceAddsLines) {
        { result ->
            if (scope != null) {
                val input = result.layoutInput
                val layoutKey = scope.document to key
                scope.owner.tableLayouts.remove(layoutKey)
                val previous = scope.owner.textLayouts.put(layoutKey, result)?.layoutInput
                // If content and width/font change together, compare against the
                // old rendered prefix laid out under the *new* constraints. A
                // plain count delta would charge for wrapping old text, or miss
                // new lines when the window gets wider in the same frame.
                val previousLines = if (updatesAddLines && previous != null && previous.text.text != input.text.text && gitHubLineGeometry(previous) != gitHubLineGeometry(input)) {
                    previousGitHubLinesAtGeometry(previous, input, scope.measurer)
                } else {
                    null
                }
                scope.owner.ledger.observe(
                    key = layoutKey,
                    document = scope.document,
                    sourceOffset = sourceOffset,
                    text = input.text.text,
                    lines = result.lineCount,
                    geometry = gitHubLineGeometry(input),
                    updatesAddLines = updatesAddLines,
                    previousLinesAtGeometry = previousLines,
                    firstAppearanceAddsLines = firstAppearanceAddsLines
                )
                scope.owner.changed.trySend(Unit)
            }
        }
    }
}

internal fun gitHubLineGeometry(input: TextLayoutInput): List<Any?> = listOf(
    input.constraints.maxWidth, input.density.density, input.density.fontScale, input.layoutDirection,
    input.style.fontSize, input.style.lineHeight, input.style.fontFamily, input.style.fontWeight,
    input.style.fontStyle, input.style.fontSynthesis, input.style.letterSpacing, input.style.fontFeatureSettings,
    input.style.textIndent, input.style.textDirection, input.style.lineBreak, input.style.hyphens,
    input.maxLines, input.softWrap, input.placeholders
)

internal fun previousGitHubLinesAtGeometry(previous: TextLayoutInput, input: TextLayoutInput, measurer: TextMeasurer): Int {
    val useCurrentPrefix = input.text.text.startsWith(previous.text.text)
    val prefix = if (useCurrentPrefix) input.text.subSequence(0, previous.text.length) else previous.text
    return measurer.measure(
        text = prefix,
        style = input.style,
        overflow = input.overflow,
        softWrap = input.softWrap,
        maxLines = input.maxLines,
        placeholders = (if (useCurrentPrefix) input.placeholders else previous.placeholders).filter { it.end <= prefix.length },
        constraints = input.constraints,
        layoutDirection = input.layoutDirection,
        density = input.density,
        fontFamilyResolver = input.fontFamilyResolver
    ).lineCount
}
