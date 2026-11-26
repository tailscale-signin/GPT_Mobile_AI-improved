package dev.chungjungsoo.gptmobile.data.openrouter

import java.net.URI

/** Catalog hints are not confirmation of the eventual routed server settings. */
internal object OpenRouterCatalogLimits {
    data class Limits(
        val context: Int?,
        val output: Int?,
        val reasoningControlAdvertised: Boolean = false,
        val temperatureSupported: Boolean? = null,
        val toolsSupported: Boolean = false,
        val reasoningEfforts: Set<String> = emptySet(),
        val reasoningMandatory: Boolean? = null,
        val observedAtMillis: Long = 0
    )
    const val MAX_AGE_MILLIS = 3_600_000L

    private data class Snapshot(val time: Long, val observedAtMillis: Long, val models: Map<String, Limits>)

    @Volatile private var snapshot = Snapshot(0, 0, emptyMap())
    private fun now() = System.nanoTime() / 1_000_000

    fun officialEndpoint(url: String?): Boolean = url.isNullOrBlank() ||
        runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host.equals("openrouter.ai", true) && uri.userInfo == null && uri.port in setOf(-1, 443)
        }.getOrDefault(false)

    @Synchronized
    fun remember(models: List<OpenRouterModelItem>, observedAtMillis: Long = System.currentTimeMillis(), wallTimeMillis: Long = System.currentTimeMillis(), monotonicMillis: Long = now()) {
        val age = wallTimeMillis - observedAtMillis
        if (observedAtMillis <= 0 || age !in 0..MAX_AGE_MILLIS) return
        snapshot = Snapshot(
            monotonicMillis - age,
            observedAtMillis,
            models.take(10_000).associate { model ->
                model.id to Limits(
                    model.contextLength?.takeIf { it > 0 },
                    model.topProvider?.maxCompletionTokens?.takeIf { it > 0 },
                    model.supportedParameters.orEmpty().any { it == "reasoning" || it == "reasoning_effort" },
                    model.supportedParameters?.contains("temperature"),
                    "tools" in model.supportedParameters.orEmpty(),
                    model.reasoning?.supportedEfforts.orEmpty().filterTo(linkedSetOf()) { it in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max") },
                    model.reasoning?.mandatory,
                    observedAtMillis
                )
            }
        )
    }

    /** Restoring old disk or picker state must not replace a more recent live catalog. */
    @Synchronized
    fun restore(models: List<OpenRouterModelItem>, observedAtMillis: Long, wallTimeMillis: Long = System.currentTimeMillis(), monotonicMillis: Long = now()) {
        if (observedAtMillis < snapshot.observedAtMillis && snapshot.models.isNotEmpty()) return
        remember(models, observedAtMillis, wallTimeMillis, monotonicMillis)
    }

    fun compatibleFallbacks(url: String?, primary: String, candidates: List<String>, promptTokens: Int, outputTokens: Int, requiresTools: Boolean): List<String> =
        candidates.distinct().filter { candidate ->
            val capability = forRequest("OPENROUTER", url, candidate)
            candidate != primary &&
                capability != null &&
                (capability.context ?: 0) >= promptTokens.toLong() + outputTokens &&
                (capability.output ?: 0) >= outputTokens &&
                (!requiresTools || capability.toolsSupported)
        }

    fun forRequest(provider: String, url: String?, model: String, routing: String? = null, monotonicMillis: Long = now()): Limits? {
        if (provider != "OPENROUTER" || !officialEndpoint(url)) return null
        val current = snapshot
        if (monotonicMillis - current.time !in 0..MAX_AGE_MILLIS) return null
        val limits = current.models[model] ?: return null
        // The aggregate context is a ceiling; top-provider output is only a default-route hint.
        return if (routing.isNullOrBlank() || routing == "{}") limits else limits.copy(output = null, reasoningControlAdvertised = false, temperatureSupported = null, reasoningEfforts = emptySet(), reasoningMandatory = null)
    }
}
