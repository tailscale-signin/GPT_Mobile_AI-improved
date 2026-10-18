package dev.chungjungsoo.gptmobile.data.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Logical acquisition units are separate from the enclosing RPC or orchestrator call count. */
object CombinedToolWork {
    fun units(arguments: JsonObject): Long {
        val nested = listOf("requests", "calls", "tool_uses", "operations").mapNotNull { arguments[it] as? JsonArray }
        if (nested.isNotEmpty()) {
            return nested.maxOf { batch ->
                batch.sumOf { value ->
                    val objectValue = value as? JsonObject
                    val argumentsValue = (objectValue?.get("arguments") ?: objectValue?.get("parameters") ?: objectValue) as? JsonObject
                    argumentsValue?.let(::units) ?: 1L
                }.coerceAtLeast(1)
            }
        }
        return listOf("queries", "urls", "documents", "sources", "paper_ids", "ids")
            .mapNotNull { (arguments[it] as? JsonArray)?.size?.toLong() }.maxOrNull()?.coerceAtLeast(1) ?: 1
    }

    fun isInspection(name: String): Boolean = name in setOf("read_url", "deep_research_read_paper", "firecrawl_scrape", "tavily_extract", "web_fetch_exa")
}

/** A second native/wrapper layer may validate again, but cannot charge the same physical tool twice. */
internal class CombinedToolDispatchScope(
    val context: CombinedExecutionContext,
    val identity: String
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CombinedToolDispatchScope>
}

/** Carries the active role through a child runner even when its tools were bound by the parent. */
internal class CombinedRunScope(val context: CombinedExecutionContext) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CombinedRunScope>
}
