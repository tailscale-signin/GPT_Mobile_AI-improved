package dev.chungjungsoo.gptmobile.presentation.ui.chat

private val sourceSectionHeading = Regex("^\\s*(?:#{1,6}\\s*)?(?:\\*\\*)?(?:sources|references|citations|bibliography)(?:\\s*:)?(?:\\*\\*)?\\s*:?[ \\t]*$", RegexOption.IGNORE_CASE)
private val sourceLink = Regex("(?<!!)\\[([^]\\n]+)]\\((https?://[^\\s]+?)\\)")
private val sourceReferenceLink = Regex("\\[([^]\\n]+)]\\[S?\\d+]")
private val sourceMarker = Regex("(?<![\\w\\])])\\[(?:S?\\d+)(?:\\s*[,;–-]\\s*S?\\d+)*]")
private val referenceDefinition = Regex("^\\s*\\[(?:S?\\d+)]:\\s*https?://", RegexOption.IGNORE_CASE)
private val inlineCode = Regex("(`+).*?\\1")
private val sourceListRow = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)?\\[[^]\\n]+]\\(https?://[^\\s]+\\)\\s*$")
private val bareSourceRow = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)?<?https?://[^\\s]+>?\\s*$", RegexOption.IGNORE_CASE)
private val inlineSourceHeading = Regex("^\\s*(?:#{1,6}\\s*)?(?:\\*\\*)?(?:sources|references|citations|bibliography)(?:\\*\\*)?\\s*:(?:\\*\\*)?\\s*(?:\\[|https?://)", RegexOption.IGNORE_CASE)

/** A display projection only: original answers and tool evidence remain saved for Sources/export. */
internal fun answerWithoutSourceListing(answer: String): String = AnswerSourceProjection().project(answer)

/** Carries source-section and code-fence state across response segments separated by tools. */
internal class AnswerSourceProjection {
    private var fence: String? = null
    private var inSources = false

    fun project(answer: String): String {
        val result = mutableListOf<String>()
        answer.lineSequence().forEach { line ->
            val trimmed = line.trimStart()
            val marker = when {
                trimmed.startsWith("```") -> "```"
                trimmed.startsWith("~~~") -> "~~~"
                else -> null
            }
            if (marker != null) {
                if (fence == null) {
                    fence = marker
                } else if (fence == marker) {
                    fence = null
                }
                if (!inSources) result += line
            } else if (fence != null) {
                if (!inSources) result += line
            } else if (sourceSectionHeading.matches(line) || inlineSourceHeading.containsMatchIn(line)) {
                inSources = true
            } else {
                if (inSources && Regex("^\\s*#{1,6}\\s+\\S").containsMatchIn(line)) inSources = false
                if (!inSources && !referenceDefinition.containsMatchIn(line) && !sourceListRow.matches(line) && !bareSourceRow.matches(line)) {
                    result += cleanSourceLinks(line)
                }
            }
        }
        return result.joinToString("\n").trimEnd()
    }

    private fun cleanSourceLinks(line: String): String = buildString {
        var start = 0
        inlineCode.findAll(line).forEach { code ->
            append(cleanProse(line.substring(start, code.range.first)))
            append(code.value)
            start = code.range.last + 1
        }
        append(cleanProse(line.substring(start)))
    }

    private fun cleanProse(value: String): String = sourceMarker.replace(sourceReferenceLink.replace(sourceLink.replace(value) { it.groupValues[1] }) { it.groupValues[1] }, "")
        .replace(Regex("[ \\t]+([.,;:!?])"), "$1")
}

internal fun sourceFilterId(source: ChatSource): String = chatSourceBrand(source.host)?.id ?: "other"

internal fun sourceFilters(sources: List<ChatSource>): List<String> =
    sources.map(::sourceFilterId).distinct().sortedWith(compareBy<String> { it == "other" }.thenBy { id -> chatSourceBrands.firstOrNull { it.id == id }?.name.orEmpty() })

internal fun sortedChatSources(sources: List<ChatSource>, filter: String? = null): List<ChatSource> =
    sources.filter { filter == null || sourceFilterId(it) == filter }
        .sortedWith(compareBy<ChatSource> { sourceFilterId(it) == "other" }.thenBy { chatSourceBrand(it.host)?.name.orEmpty() }.thenBy { it.host }.thenBy { it.title })
