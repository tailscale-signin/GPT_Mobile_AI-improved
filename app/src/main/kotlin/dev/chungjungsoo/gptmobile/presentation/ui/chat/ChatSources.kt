package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.recoveryResult
import dev.chungjungsoo.gptmobile.data.agent.tool.parseSearchPayload
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class ChatSource(val url: String, val title: String, val host: String) {
    val compactLink: String
        get() = runCatching {
            val uri = URI(url)
            host + uri.rawPath.orEmpty().takeUnless { it == "/" }.orEmpty()
        }.getOrDefault(host)
}

internal data class ChatSources(val engines: List<String>, val sources: List<ChatSource>) {
    val isEmpty: Boolean get() = engines.isEmpty() && sources.isEmpty()
}

private val sourcePayloadKeys = setOf(
    "sources", "results", "result", "data", "web", "organic", "organic_results",
    "search_results", "items", "content", "text", "structuredContent", "resourceLinks",
    "engines", "detail", "citations", "references", "evidence", "local_evidence", "pages", "urls", "links",
    "products", "product_results"
)
private val sourceUrls = Regex("https?://[^\\s<>\"`\\\\]+", RegexOption.IGNORE_CASE)
private val sourceMarkdownLinks = Regex("(?<!!)\\[([^]\\n]+)]\\((https?://[^\\s]+?)\\)(?=\\s|$|[.,;:!?])", RegexOption.IGNORE_CASE)
private val researchTool = Regex("search|read_url|read_web|open_url|fetch|crawl|browse|delegate|research", RegexOption.IGNORE_CASE)
private val unavailableSourceStates = setOf("unavailable", "failed", "error", "canceled", "cancelled", "skipped")

/** Reads persisted provenance only; opening the source picker never fetches a favicon or page. */
internal fun collectChatSources(answer: String, events: List<ToolEvent>): ChatSources {
    val sources = linkedMapOf<String, ChatSource>()
    val engines = linkedMapOf<String, String>()

    fun addSource(rawUrl: String, title: String? = null) {
        var url = rawUrl.trim().trimEnd('.', ',', ';', ']', '}')
        while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.userInfo != null) return
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: return
        val query = uri.rawQuery?.split('&')?.filterNot {
            val key = it.substringBefore('=').lowercase(Locale.ROOT)
            key.startsWith("utm_") || key in setOf("fbclid", "gclid", "msclkid")
        }?.joinToString("&").orEmpty()
        val port = uri.port.takeUnless { it == -1 || (it == 80 && uri.scheme == "http") || (it == 443 && uri.scheme == "https") }
        val key = "${uri.scheme.lowercase(Locale.ROOT)}://$host${port?.let { ":$it" }.orEmpty()}${uri.rawPath.orEmpty().trimEnd('/')}" +
            query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        val existing = sources[key]
        val label = title?.trim()?.takeIf { it.isNotBlank() && it != rawUrl && it != url } ?: host
        if (existing == null) {
            sources[key] = ChatSource(url, label, host)
        } else if (existing.title == existing.host && label != host) {
            sources[key] = existing.copy(title = label)
        }
    }

    fun addEngine(label: String) {
        val trimmed = label.trim().takeIf { it.isNotBlank() } ?: return
        if (trimmed.lowercase(Locale.ROOT) in setOf("built-in search", "multi-engine search", "web search")) return
        val brand = chatSearchEngineBrand(trimmed)
        val key = brand?.id ?: trimmed.lowercase(Locale.ROOT)
        engines.putIfAbsent(key, brand?.name ?: trimmed)
    }

    fun addLinks(text: String, includeBareUrls: Boolean) {
        sourceMarkdownLinks.findAll(text).forEach { addSource(it.groupValues[2], it.groupValues[1]) }
        if (includeBareUrls) sourceUrls.findAll(text).forEach { addSource(it.value) }
    }

    fun visit(value: JsonElement?, depth: Int = 0) {
        if (depth > 10) return
        when (value) {
            is JsonArray -> value.forEach { visit(it, depth + 1) }
            is JsonObject -> {
                if (value.sourceString("status")?.lowercase(Locale.ROOT) in unavailableSourceStates) return
                if (value.sourceString("schema") == "amazon_products_v1") value.sourceString("provider")?.let(::addEngine)
                value.sourceString("engine")?.let { label ->
                    addEngine(chatSearchEngineBrand("${value.sourceString("tool").orEmpty()} $label")?.name ?: label)
                }
                val engineValues = value["engines"] as? JsonArray
                engineValues?.filterIsInstance<JsonPrimitive>()?.mapNotNull { it.contentOrNull }?.forEach(::addEngine)
                val url = value.sourceString("url", "link", "uri", "source_url", "sourceUrl")
                if (url != null) {
                    addSource(url, value.sourceString("title", "name", "label"))
                    // Article bodies can contain many outbound links that were never gathered.
                    return
                }
                value.filterKeys { it in sourcePayloadKeys }.values.forEach { visit(it, depth + 1) }
            }
            is JsonPrimitive -> if (value.isString) {
                val text = value.content
                if (sourceUrls.matches(text)) addSource(text)
                visit(parseSearchPayload(text), depth + 1)
                addLinks(text, includeBareUrls = false)
            }
            else -> Unit
        }
    }

    events.distinctBy { it.eventId }.forEach { event ->
        if (event.isError || event.status != ToolEventStatus.COMPLETED) return@forEach
        if (!researchTool.containsMatchIn("${event.toolName} ${event.modelToolName}") &&
            event.toolName !in setOf("amazon_get_products", "web_data_amazon_product")
        ) {
            return@forEach
        }
        val payload = event.recoveryResult() ?: return@forEach
        val reader = Regex("read|fetch|crawl|browse", RegexOption.IGNORE_CASE).containsMatchIn("${event.toolName} ${event.modelToolName}")
        visit(parseSearchPayload(payload))
        // Named single-engine tools retain provenance even without a multi-search envelope.
        val engine = chatSearchEngineBrand("${event.connectionNameSnapshot.orEmpty()} ${event.toolName} ${event.modelToolName}")
        if (engine != null) {
            addEngine(engine.name)
        } else if (event.toolName.contains("search", true)) {
            event.connectionNameSnapshot?.takeUnless { it.lowercase(Locale.ROOT) in setOf("multi-engine search", "built-in search") }?.let(::addEngine)
        }
        if (!reader && payload.trimStart().firstOrNull() !in setOf('{', '[')) addLinks(payload, includeBareUrls = false)
        if (reader) {
            // Readers often return plain text and keep the requested URL only in arguments.
            visit(parseSearchPayload(event.arguments))
        }
    }
    addLinks(answer, includeBareUrls = true)
    return ChatSources(engines.values.toList(), sources.values.toList())
}

private fun JsonObject.sourceString(vararg keys: String): String? = keys.firstNotNullOfOrNull {
    (this[it] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { value -> value.isNotBlank() }
}
