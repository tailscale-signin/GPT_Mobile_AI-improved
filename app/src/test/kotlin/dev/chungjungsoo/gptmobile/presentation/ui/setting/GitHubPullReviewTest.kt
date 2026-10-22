package dev.chungjungsoo.gptmobile.presentation.ui.setting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubPullReviewTest {
    private fun packet(sections: String, head: String = "head", base: String = "base", number: Int = 7): JsonObject = Json.parseToJsonElement(
        """{"pull_request":{"number":$number,"base":{"sha":"$base"}},"expected_head":"$head",$sections}"""
    ).jsonObject

    @Test fun pagingFilesAndChecksRetainsTheOtherCollectionAndClearsExhaustedCursors() {
        val first = packet(""""files_included":true,"checks_included":true,"files":["file1"],"page":1,"files_continuation":{"page":2},"checks":{"page":1},"checks_continuation":{"check_page":2}""")
        val files = packet(""""files_included":true,"checks_included":false,"files":["file2"],"page":2,"has_more":false""")
        val afterFiles = retainGitHubPullReviewSections(first, files)
        assertEquals(files["files"], afterFiles["files"])
        assertEquals(first["checks"], afterFiles["checks"])
        assertEquals(first["checks_continuation"], afterFiles["checks_continuation"])
        assertFalse("files_continuation" in afterFiles)
        val checks = packet(""""files_included":false,"checks_included":true,"checks":{"page":2,"has_more":false}""")
        val afterChecks = retainGitHubPullReviewSections(afterFiles, checks)
        assertEquals(files["files"], afterChecks["files"])
        assertEquals(JsonPrimitive(2), afterChecks["page"])
        assertEquals(checks["checks"], afterChecks["checks"])
        assertFalse("checks_continuation" in afterChecks)
        assertFalse("files_continuation" in afterChecks)
    }

    @Test fun changedHeadBaseOrPullRequestNeverRetainsOldReviewSections() {
        val first = packet(""""files":["file"],"checks":{"page":1},"checks_continuation":{"check_page":2}""")
        val sections = """"files_included":true,"checks_included":false,"files":[]"""
        for (changed in listOf(packet(sections, head = "changed"), packet(sections, base = "changed"), packet(sections, number = 8))) {
            assertEquals(changed, retainGitHubPullReviewSections(first, changed))
        }
    }
}
