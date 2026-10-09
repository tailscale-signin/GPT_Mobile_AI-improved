package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class SearchCandidate(
    val source: JsonObject,
    val selectionId: String,
    val ownerIndex: Int,
    val providerLabel: String,
    val providerRank: Int,
    val url: ValidatedSearchUrl,
    val fingerprint: SnippetFingerprint
) {
    companion object {
        fun create(source: JsonObject, selectionId: String, ownerIndex: Int, label: String, rank: Int): SearchCandidate? {
            fun text(key: String) = (source[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val url = SearchUrlIdentity.parse(text("url")) ?: return null
            return SearchCandidate(source, selectionId, ownerIndex, label, rank, url, SnippetFingerprint.create(text("snippet"), text("title"), text("publishedDate").ifBlank { null }))
        }
    }
}
