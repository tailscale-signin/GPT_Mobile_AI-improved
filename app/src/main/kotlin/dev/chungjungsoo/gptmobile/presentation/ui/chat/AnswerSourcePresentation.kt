package dev.chungjungsoo.gptmobile.presentation.ui.chat

private val sourceHeading = Regex("^\\s*(?:#{1,6}\\s*)?(?:\\*\\*)?(?:sources?|references?|citations?|bibliography|source links)\\s*:?(?:\\*\\*)?\\s*:?\\s*$", RegexOption.IGNORE_CASE)
private val citationMarker = Regex("(?<![\\w`])\\[(?:S\\d+|\\d+(?:\\s*[–-]\\s*\\d+)?)\\](?!\\()", RegexOption.IGNORE_CASE)
private val explicitCitationMarker = Regex("(?<![\\w`])\\[S\\d+](?!\\()", RegexOption.IGNORE_CASE)
private val sourceLabel = Regex("^\\s*(?:[-*+]\\s*)?(?:\\*\\*)?(?:sources?|references?|citations?)(?:\\*\\*)?\\s*:", RegexOption.IGNORE_CASE)
private val citationLink = Regex("\\[(?:S\\d+|\\d+(?:\\s*[,–-]\\s*\\d+)*)]\\(https?://[^\\s)]+\\)", RegexOption.IGNORE_CASE)
private val markdownSourceLink = Regex("\\[([^]\\n]+)]\\(https?://[^\\s)]+\\)")
private val sourceUrl = Regex("<?https?://[^\\s<>]+>?")
private val punctuationSpace = Regex(" +([.,;:!?])")
private val inlineCode = Regex("(`+)(.*?)\\1")

private fun withoutReferences(line: String, hasReferences: Boolean): String = line
    .replace(citationLink, "")
    .replace(markdownSourceLink, "$1")
    .replace(sourceUrl) { match -> match.value.removeSuffix(">").takeLastWhile { it in ".,;:!?" } }
    .replace(if (hasReferences) citationMarker else explicitCitationMarker, "")
    .replace(punctuationSpace, "$1")

private fun preserveInlineCode(line: String, hasReferences: Boolean): String = buildString {
    var start = 0
    inlineCode.findAll(line).forEach { code ->
        append(withoutReferences(line.substring(start, code.range.first), hasReferences))
        append(code.value)
        start = code.range.last + 1
    }
    append(withoutReferences(line.substring(start), hasReferences))
}

/** Presentation only: keep the original answer and provenance available for the Sources picker. */
internal fun answerWithoutSourceLists(answer: String): String {
    val lines = answer.lines()
    val hasReferences = sourceUrl.containsMatchIn(answer) || lines.any(sourceHeading::matches)
    val output = mutableListOf<String>()
    var fence: String? = null
    var inReferenceList = false
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (marker != null) {
            fence = if (fence == marker) null else fence ?: marker
            output += line
            index++
            continue
        }
        if (fence != null || line.startsWith("    ") || line.startsWith('\t')) {
            output += line
            index++
            continue
        }
        if (sourceHeading.matches(line)) {
            val firstContent = (index + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
            val nextIsHeading = firstContent?.let { lines[it].trimStart().startsWith('#') } == true
            val nextLooksLikeReference = firstContent?.let {
                lines[it].contains("https://") || lines[it].contains("http://") || citationMarker.containsMatchIn(lines[it])
            } == true
            if (firstContent == null || nextIsHeading || nextLooksLikeReference) {
                inReferenceList = true
                index++
                continue
            }
        }
        if (inReferenceList) {
            val referenceLine = line.contains("https://") || line.contains("http://") || citationMarker.containsMatchIn(line)
            if (line.isBlank() || referenceLine) {
                index++
                continue
            }
            inReferenceList = false
        }
        if (sourceLabel.containsMatchIn(line) && (line.contains("https://") || line.contains("http://"))) {
            index++
            continue
        }
        output += preserveInlineCode(line, hasReferences)
        index++
    }
    return output.joinToString("\n").trimEnd()
}

internal fun sourceGroupId(source: ChatSource): String = chatSourceBrand(source.host)?.id ?: "other"

internal fun filterChatSources(sources: List<ChatSource>, group: String?): List<ChatSource> = sources
    .filter { group == null || sourceGroupId(it) == group }
    .sortedWith(compareBy<ChatSource> { sourceGroupId(it) == "other" }.thenBy { it.host }.thenBy { it.title })
