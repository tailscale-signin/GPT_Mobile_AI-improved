package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Display-only data resolved from recalled IDs; never persisted in chat metadata. */
data class DebugMemorySource(val label: String, val value: String)

private val debugMemoryWhitespace = Regex("\\s+")

internal fun rememberedMemoryIdsFromToolResult(result: String?): List<String> {
    val json = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(result.orEmpty()) as? kotlinx.serialization.json.JsonObject
    }.getOrNull() ?: return emptyList()
    return (json["recalledFactIds"] as? kotlinx.serialization.json.JsonArray)
        .orEmpty()
        .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
        .distinct()
}

internal fun isMemoryRecallTool(toolName: String): Boolean {
    val leaf = toolName.substringAfterLast("__").trim().lowercase()
    return leaf == "memory" || leaf == "memory_recall" || leaf == "local_memory" || leaf == "recall_memory"
}

internal fun debugMemorySourceRows(sources: Iterable<DebugMemorySource>): List<String> {
    val seen = linkedSetOf<String>()
    val rows = mutableListOf<String>()
    for (source in sources) {
        val row = source.displayLabel()
        val key = row.lowercase()
        if (row.isNotBlank() && seen.add(key)) rows += row
    }
    return rows
}

internal fun DebugMemorySource.displayLabel(): String {
    val cleanLabel = label.cleanDebugMemoryText()
    val cleanValue = value.cleanDebugMemoryText()
    if (cleanLabel.isBlank()) return cleanValue
    if (cleanValue.isBlank()) return cleanLabel

    if (!cleanLabel.contains(cleanValue, ignoreCase = true)) return cleanLabel

    val prefix = Regex(Regex.escape(cleanValue), RegexOption.IGNORE_CASE)
        .replace(cleanLabel, " ")
        .cleanDebugMemoryText()
        .trim(' ', ':', '·', '-', '—')
    val category = when {
        prefix.equals("observation", ignoreCase = true) -> "Observation"
        prefix.startsWith("User profile", ignoreCase = true) -> "User profile"
        prefix.startsWith("User goal", ignoreCase = true) -> "User goal"
        prefix.startsWith("User discusses", ignoreCase = true) -> "User discusses"
        prefix.length in 1..28 -> prefix.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        else -> "Memory"
    }
    return "$category: $cleanValue"
}

private fun String.cleanDebugMemoryText(): String =
    asSequence()
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .joinToString("")
        .replace('�', ' ')
        .replace(debugMemoryWhitespace, " ")
        .trim()

/** Highlight exact shared phrases, not guessed paraphrases or isolated common words. */
private val memoryWord = Regex("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*")

internal fun memoryHighlightRanges(text: String, values: List<String>): List<IntRange> {
    val responseWords = memoryWord.findAll(text).toList()
    val byWord = responseWords.indices.groupBy { responseWords[it].value.lowercase() }
    val ranges = mutableListOf<IntRange>()
    for (value in values.distinct()) {
        val words = memoryWord.findAll(value).map { it.value.lowercase() }.toList()
        for (start in words.indices) {
            for (responseStart in byWord[words[start]].orEmpty()) {
                var count = 0
                while (start + count < words.size &&
                    responseStart + count < responseWords.size &&
                    words[start + count] == responseWords[responseStart + count].value.lowercase()
                ) {
                    count++
                }
                if (count < 2 && words.size != 1) continue
                val range = responseWords[responseStart].range.first..responseWords[responseStart + count - 1].range.last
                if (range.last - range.first >= 3) ranges += range
            }
        }
    }
    val merged = mutableListOf<IntRange>()
    for (range in ranges.sortedBy { it.first }) {
        val previous = merged.lastOrNull()
        if (previous != null && range.first <= previous.last + 1) {
            merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}
