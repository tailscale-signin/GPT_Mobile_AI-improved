package dev.chungjungsoo.gptmobile.data.research.feature

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReaderRouterTest {
    private val url = "https://example.org/article"
    private val paper = AcademicPaper("doi:10.1234/example", "Fixture", url, openAccessUrl = url)
    private val initial = AcademicEvidence(paper.paperId, "abstract", url, 1, researchHash("Abstract"), "Abstract", "abstract_only")
    private val sessions = mockk<ResearchSessionStore>()
    private val tool = mockk<AgentTool>()
    private val readers = listOf(ResolvedAgentTool(tool, null, null, "read_url", "read_url"))

    private fun response(text: String, finalUrl: String = url) = AgentToolResult(
        "call",
        ToolResultContent.Json(
            buildJsonObject {
                put("url", finalUrl)
                put("text", text)
            }
        ),
        false
    )

    @Test fun abstractCanBeUpgradedByAuthorizedDocumentReader() = runBlocking {
        val body = "Methods and results with sufficient article text."
        coEvery { tool.execute(any(), any()) } returns response(body)
        val result = ResearchReaderRouter.fallback(paper, initial, readers, sessions, emptyList(), emptyList()) { true }
        assertEquals("read", result.retrievalStatus)
        assertEquals(body, result.text)
        assertEquals(researchHash(body), result.textHash)
        coVerify(exactly = 1) { tool.execute(any(), any()) }
    }

    @Test fun readerFailurePreservesPreviouslyReadableAbstract() = runBlocking {
        coEvery { tool.execute(any(), any()) } throws java.net.UnknownHostException("offline")
        val offline = ResearchReaderRouter.fallback(paper, initial, readers, sessions, emptyList(), emptyList()) { true }
        assertEquals(initial.text, offline.text)
        assertEquals("abstract_only", offline.retrievalStatus)
        assertEquals("NETWORK_UNAVAILABLE", offline.failureCode)
        coEvery { tool.execute(any(), any()) } returns response("Verify you are human. CAPTCHA")
        val blocked = ResearchReaderRouter.fallback(paper, initial, readers, sessions, emptyList(), emptyList()) { true }
        assertEquals(initial.text, blocked.text)
        assertEquals("abstract_only", blocked.retrievalStatus)
        assertEquals("SOURCE_ACCESS_BLOCKED", blocked.failureCode)
    }

    @Test fun permissionAndDomainRestrictionsNeverDispatchFallback() = runBlocking {
        for (state in listOf("blocked", "permission_denied", "authentication_required")) {
            val evidence = initial.copy(retrievalStatus = state)
            assertEquals(evidence, ResearchReaderRouter.fallback(paper, evidence, readers, sessions, emptyList(), emptyList()) { true })
        }
        assertEquals(initial, ResearchReaderRouter.fallback(paper, initial, readers, sessions, emptyList(), listOf("example.org")) { true })
        assertEquals(initial.copy(retrievalStatus = "permission_denied", failureCode = "PERMISSION_REVOKED"), ResearchReaderRouter.fallback(paper, initial, readers, sessions, emptyList(), emptyList()) { false })
        coVerify(exactly = 0) { tool.execute(any(), any()) }
    }

    @Test fun excludedRedirectAndCancellationCannotBecomeAcceptedEvidence() = runBlocking {
        coEvery { tool.execute(any(), any()) } returns response("Other article text", "https://excluded.org/article")
        assertEquals(initial, ResearchReaderRouter.fallback(paper, initial, readers, sessions, emptyList(), listOf("excluded.org")) { true })
        coEvery { tool.execute(any(), any()) } throws CancellationException("stopped")
        val failure = runCatching { ResearchReaderRouter.fallback(paper, initial, readers, sessions, emptyList(), emptyList()) { true } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
