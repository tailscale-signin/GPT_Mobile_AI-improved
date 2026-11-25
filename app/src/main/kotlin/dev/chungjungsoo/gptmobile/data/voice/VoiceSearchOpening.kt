package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Only the user's own short topic can become speech. Tool arguments are used solely to confirm a match. */
internal object VoiceSearchOpening {
    private val lookup = Regex("""(?:please\s+)?(?:what(?:['’]s| is| are)|(?:look up|check|search for|find out about))\s+([\p{L}\p{N}][\p{L}\p{N} '’-]{0,68})[?.!]?""", RegexOption.IGNORE_CASE)
    private val specialRequest = Regex("""\b(?:my|our|your|exactly|only|verbatim|reply|answer|respond|instead|say|word|words|password|token|secret|pin|code|and|then)\b""", RegexOption.IGNORE_CASE)
    private val word = Regex("""[\p{L}\p{N}]+""")
    private val common = setOf("a", "an", "the", "of", "to", "at", "for", "on", "in", "is", "it", "my", "me", "that", "this", "latest", "current", "today", "now", "please", "about")

    fun line(prompt: String, runId: String, events: List<ToolEvent>): String? {
        if (prompt.length !in 4..112 || specialRequest.containsMatchIn(prompt)) return null
        val topic = lookup.matchEntire(prompt.trim())?.groupValues?.get(1)?.trim() ?: return null
        val words = word.findAll(topic.lowercase()).map { it.value }.toList()
        if (words.size !in 1..8 || words.any { it.length > 20 }) return null
        val anchors = words.filter { it !in common && it.length >= 2 }.toSet()
        if (anchors.isEmpty()) return null
        val running = events.any { event ->
            if (event.runId != runId ||
                event.toolName != "web_search" ||
                event.modelToolName != "web_search" ||
                event.connectionUidSnapshot != null ||
                event.status != ToolEventStatus.RUNNING ||
                event.startedAt == null ||
                event.isError ||
                event.arguments.length > 2048
            ) {
                return@any false
            }
            val args = runCatching { Json.parseToJsonElement(event.arguments) as? JsonObject }.getOrNull()
            val query = (args?.get("query") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@any false
            val queryWords = word.findAll(query.lowercase()).map { it.value }.toSet()
            anchors.all(queryWords::contains)
        }
        return if (running) "I’m checking $topic." else null
    }
}
