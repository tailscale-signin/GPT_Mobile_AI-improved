package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubReadProjectionTest {
    @Test fun compactReadPreservesSourceAndRecoveryWhileOmittingVerboseGraphEdges() {
        val source = Json.parseToJsonElement("""{"items":[{"content":"exact source","continuation":{"start_line":10}}],"context_graph":{"compiler_resolution":false,"edges":[{"from":"A.kt","to":"B.kt"}],"suggested_ranges":[{"path":"B.kt"}]}}""").jsonObject
        val compact = GitHubResultProjection.project("github_read", buildJsonObject {}, source)
        assertEquals(source["items"], compact["items"])
        assertFalse(compact.getValue("context_graph").jsonObject.containsKey("edges"))
        assertEquals(JsonPrimitive(false), compact.getValue("context_graph").jsonObject["compiler_resolution"])
        assertEquals(source, GitHubResultProjection.project("github_read", buildJsonObject { put("response_detail", "full") }, source))
    }
}
