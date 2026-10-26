package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GitHubEngineeringCiHttpGatewayTest {
    private val sha = "a".repeat(40)
    private fun run(id: Int) = JSONObject().put("id", id).put("name", "test-$id").put("head_sha", sha)
        .put("app", JSONObject().put("id", 7)).put("status", "completed").put("conclusion", "success")
    private fun api(reply: (String) -> String) = object : GitHubEngineeringApi({ null }) {
        override suspend fun request(path: String, method: String, body: JSONObject?): String = reply(path)
    }

    @Test fun allCheckPagesAndLegacyStatusesAreFetched() = runBlocking {
        val visited = mutableListOf<String>()
        val gateway = GitHubEngineeringCiHttpGateway(
            { null },
            api { path ->
                visited += path
                when {
                    "/check-runs?" in path -> {
                        val rows = if (path.endsWith("page=1")) (1..100) else (101..101)
                        JSONObject().put("total_count", 101).put("check_runs", JSONArray().apply { rows.forEach { put(run(it)) } }).toString()
                    }
                    "/statuses?" in path -> JSONArray().put(JSONObject().put("id", 1001).put("context", "legacy").put("state", "success")).toString()
                    else -> error("Unexpected request")
                }
            }
        )
        val checks = gateway.fetch("owner", "repo", sha)
        assertEquals(102, checks.size)
        assertEquals("status", checks.last().source)
        assertTrue(visited.any { it.endsWith("page=2") })
    }

    @Test fun truncatedCollectionNeverPasses() = runBlocking {
        val gateway = GitHubEngineeringCiHttpGateway({ null }, api { JSONObject().put("total_count", 101).put("check_runs", JSONArray().put(run(1))).toString() })
        assertTrue(runCatching { gateway.fetch("owner", "repo", sha) }.isFailure)
    }

    @Test fun protectionAndRulesetAppIdentitiesAreRetained() = runBlocking {
        val gateway = GitHubEngineeringCiHttpGateway(
            { null },
            api { path ->
                if ("/protection/" in path) {
                    """{"checks":[{"context":"unit","app_id":7}],"contexts":["unit"]}"""
                } else {
                    """[{"type":"required_status_checks","parameters":{"required_status_checks":[{"context":"legacy","integration_id":null}]}}]"""
                }
            }
        )
        assertEquals(setOf(EngineeringRequiredCheck("unit", 7), EngineeringRequiredCheck("legacy")), gateway.requiredChecks("owner", "repo", "main"))
    }
}
