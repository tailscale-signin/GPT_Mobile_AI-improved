package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineageAccumulator
import dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin

/** Publish only completed public progress tags; reasoning and unfinished answers remain private. */
internal class GitHubPublicProgress(private val publish: suspend (String, OutputLineage) -> Unit) {
    private val parser = PublicProgressParser()
    private val progress = StringBuilder()
    private val lineage = OutputLineageAccumulator()

    suspend fun accept(event: ProviderEvent.TextDelta): String {
        if (!parser.hasPendingText && !parser.insideProgress && progress.isEmpty()) lineage.reset()
        lineage.append(event.lineage)
        val answer = StringBuilder()
        parser.accept(event.text).forEach { (isProgress, text) ->
            if (isProgress) {
                if (progress.length < MAX_PROGRESS_CHARACTERS) progress.append(text.take(MAX_PROGRESS_CHARACTERS - progress.length))
            } else {
                answer.append(text)
            }
        }
        if (!parser.insideProgress && progress.isNotEmpty()) {
            val text = progress.toString().trim()
            if (text.isNotEmpty() && lineage.lineage.origin == OutputOrigin.MODEL_TEXT) publish(text, lineage.lineage)
            progress.clear()
        }
        return answer.toString()
    }

    fun finish(): String {
        val answer = parser.accept("", flush = true).filterNot { it.first }.joinToString("") { it.second }
        parser.reset()
        progress.clear()
        lineage.reset()
        return answer
    }

    private companion object {
        const val MAX_PROGRESS_CHARACTERS = 2400
    }
}
