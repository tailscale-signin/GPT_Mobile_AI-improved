package dev.chungjungsoo.gptmobile.data.openrouter

import java.net.URI

/** Catalog hints are not confirmation of the eventual routed server settings. */
internal object OpenRouterCatalogLimits {
    data class Limits(val context: Int?, val output: Int?)
    private data class Snapshot(val time: Long, val models: Map<String, Limits>)

    @Volatile private var snapshot = Snapshot(0, emptyMap())
    private fun now() = System.nanoTime() / 1_000_000

    fun officialEndpoint(url: String?): Boolean = url.isNullOrBlank() ||
        runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host.equals("openrouter.ai", true) && uri.userInfo == null && uri.port in setOf(-1, 443)
        }.getOrDefault(false)

    fun remember(models: List<OpenRouterModelItem>) {
        snapshot = Snapshot(
            now(),
            models.take(10_000).associate { model ->
                model.id to Limits(model.contextLength?.takeIf { it > 0 }, model.topProvider?.maxCompletionTokens?.takeIf { it > 0 })
            }
        )
    }

    fun forRequest(provider: String, url: String?, model: String, routing: String? = null): Limits? {
        if (provider != "OPENROUTER" || !officialEndpoint(url)) return null
        val current = snapshot
        if (now() - current.time !in 0..3_600_000) return null
        val limits = current.models[model] ?: return null
        // The aggregate context is a ceiling; top-provider output is only a default-route hint.
        return if (routing.isNullOrBlank() || routing == "{}") limits else limits.copy(output = null)
    }
}
