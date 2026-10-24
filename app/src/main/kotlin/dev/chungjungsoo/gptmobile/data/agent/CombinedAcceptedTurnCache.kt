package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Parsed immutable bytes only; this cache cannot resolve references or grant execution authority. */
internal object CombinedAcceptedTurnCache {
    internal const val MAX_ENTRIES = 24
    internal const val MAX_CHARACTERS = 4_000_000
    private const val MAX_ENTRY_CHARACTERS = 1_000_000
    private val json = Json { ignoreUnknownKeys = true }
    internal data class Parsed(val accepted: CombinedAcceptedTurn?, val context: CombinedExecutionContext?)
    private data class Entry(val raw: String, val parsed: Parsed, val chats: MutableSet<Int>)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var characters = 0
    private var evictionGeneration = 0L

    fun read(raw: String?, ownerChatId: Int? = null): Parsed? {
        if (raw.isNullOrBlank()) return null
        val key = researchHash(raw)
        val generation: Long
        synchronized(entries) {
            entries[key]?.takeIf { it.raw == raw }?.let { entry ->
                ownerChatId?.let(entry.chats::add)
                return entry.parsed
            }
            generation = evictionGeneration
        }
        val parsed = runCatching {
            val root = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching null
            val accepted = if (root.containsKey("context") && root.containsKey("tools") && root["policyRecordKind"]?.toString() in listOf(null, "\"COMBINED_ACCEPTED_TURN\"")) {
                json.decodeFromJsonElement(CombinedAcceptedTurn.serializer(), root).takeIf { it.version == 1 }
            } else {
                null
            }
            Parsed(accepted, accepted?.context ?: root["combined"]?.let { json.decodeFromJsonElement(CombinedExecutionContext.serializer(), it) })
        }.getOrNull() ?: return null
        if (raw.length <= MAX_ENTRY_CHARACTERS) {
            synchronized(entries) {
                if (generation != evictionGeneration) return parsed
                val previous = entries.remove(key)
                previous?.let { characters -= it.raw.length }
                entries[key] = Entry(
                    raw,
                    parsed,
                    mutableSetOf<Int>().apply {
                        if (previous?.raw == raw) addAll(previous.chats)
                        ownerChatId?.let(::add)
                        parsed.context?.chatId?.takeIf { it > 0 }?.let(::add)
                    }
                )
                characters += raw.length
                while (entries.size > MAX_ENTRIES || characters > MAX_CHARACTERS) {
                    val removed = requireNotNull(entries.remove(entries.keys.first()))
                    characters -= removed.raw.length
                }
            }
        }
        return parsed
    }

    fun forgetChat(chatId: Int) = synchronized(entries) {
        evictionGeneration++
        val stale = entries.filterValues { chatId in it.chats }.keys.toList()
        stale.forEach { key -> entries.remove(key)?.let { characters -= it.raw.length } }
    }

    internal fun retainedSize(): Pair<Int, Int> = synchronized(entries) { entries.size to characters }
    internal fun clear() = synchronized(entries) {
        entries.clear()
        characters = 0
        evictionGeneration++
    }
}
