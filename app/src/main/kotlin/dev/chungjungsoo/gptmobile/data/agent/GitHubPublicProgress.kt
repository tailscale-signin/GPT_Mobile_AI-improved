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
        parser.acceptSegments(event.text).forEach { segment ->
            if (segment.progress) {
                if (progress.length < MAX_PROGRESS_CHARACTERS) progress.append(segment.text.take(MAX_PROGRESS_CHARACTERS - progress.length))
            } else {
                answer.append(segment.text)
                // Any opening marker after ordinary text starts in this event;
                // an earlier pending marker that became answer text cannot own it.
                lineage.reset()
                lineage.append(event.lineage)
            }
            if (segment.closesProgress) {
                val text = progress.toString().trim()
                if (text.isNotEmpty() && lineage.lineage.origin == OutputOrigin.MODEL_TEXT) publish(text, lineage.lineage)
                progress.clear()
                // A later block or partial opening marker in this same event
                // belongs to this event, not the block that just ended.
                lineage.reset()
                lineage.append(event.lineage)
            }
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
