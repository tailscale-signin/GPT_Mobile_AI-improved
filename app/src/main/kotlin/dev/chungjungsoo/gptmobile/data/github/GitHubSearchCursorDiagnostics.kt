package dev.chungjungsoo.gptmobile.data.github

import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Typed observations at the failing continuation branch, without query or repository content. */
internal object GitHubSearchCursorDiagnostics {
    enum class State { RETAINED_SEARCH_MISSING, PAGE_BOUNDARY_UNOBSERVED }

    val fields = setOf("search_cursor_state", "search_id_hash", "search_cursor_hash")

    fun observation(state: State, searchId: String, cursor: String): JsonObject = buildJsonObject {
        put("search_cursor_state", state.name)
        hash("search-id", searchId)?.let { put("search_id_hash", it) }
        hash("search-cursor", cursor)?.let { put("search_cursor_hash", it) }
    }

    private fun hash(kind: String, value: String): String? = value.takeIf { it.isNotBlank() && it.length <= 256 }?.let {
        MessageDigest.getInstance("SHA-256")
            .digest("gptmobile.search-diagnostics.v1:$kind:$it".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
