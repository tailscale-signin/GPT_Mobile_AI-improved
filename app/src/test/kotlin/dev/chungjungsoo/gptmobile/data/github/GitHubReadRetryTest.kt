package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GitHubReadRetryTest {
    @Test fun nativeCiReadRecoversFrom502WithoutRegeneration() = runTest {
        var attempts = 0
        val http = HttpClient(
            MockEngine {
                if (++attempts < 3) respond("Server Error", HttpStatusCode.BadGateway) else respond("""{"total_count":0,"workflow_runs":[]}""")
            }
        )
        try {
            val result = GitHubTool("token", http).execute(
                "runs",
                buildJsonObject {
                    put("action", "list_workflow_runs")
                    put("owner", "o")
                    put("repo", "r")
                }
            )
            assertFalse(result.isError)
            assertEquals(3, attempts)
        } finally {
            http.close()
        }
    }

    @Test fun workspaceReadRecoversFromReset() = runTest {
        var attempts = 0
        val http = HttpClient(
            MockEngine {
                if (++attempts == 1) throw IOException("stream was reset: CANCEL")
                respond("""{"id":1,"full_name":"o/r"}""")
            }
        )
        try {
            GitHubWorkspaceClient("token", http).execute(
                "get_repository",
                buildJsonObject {
                    put("owner", "o")
                    put("repo", "r")
                }
            )
            assertEquals(2, attempts)
        } finally {
            http.close()
        }
    }
}
