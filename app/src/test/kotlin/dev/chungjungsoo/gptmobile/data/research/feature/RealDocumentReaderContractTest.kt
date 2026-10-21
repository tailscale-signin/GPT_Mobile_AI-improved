package dev.chungjungsoo.gptmobile.data.research.feature

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.AndroidResearchWorkflow
import dev.chungjungsoo.gptmobile.data.agent.tool.ReadUrlTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import dev.chungjungsoo.gptmobile.data.research.ResearchDocumentResult
import dev.chungjungsoo.gptmobile.data.research.ResearchJournal
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper
import dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate
import dev.chungjungsoo.gptmobile.data.research.enrichResearchSourceMetadata
import dev.chungjungsoo.gptmobile.data.research.researchHash
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealDocumentReaderContractTest {
    private val body = "Methods: 24 participants were randomized. Results: no measured difference in performance."

    @Test fun headOnlyCitationMetadataReachesRealWorkflowWithoutChangingCaptureAuthorityOrVersion() = runBlocking {
        ResearchConnectivityGate.shared.reset()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val doi = "10.1234/head-only"
        val html = "<html><head><meta name='citation_doi' content='$doi'><meta name='citation_pmid' content='45678'><meta name='citation_pmcid' content='PMC45678'><meta name='citation_article_type' content='randomized controlled trial'></head><body><article><p>$body</p></article></body></html>"
        server.createContext("/") { exchange ->
            val bytes = html.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://fixture-citation.example:${server.address.port}/article"
            val tool = ReadUrlTool(dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }, allowAddress = { it.isLoopbackAddress })
            val result = tool.execute(
                "head-only",
                buildJsonObject {
                    put("url", url)
                    put("extractDocument", true)
                }
            )
            val document = requireNotNull(ResearchDocumentResult.fromJson((result.content as ToolResultContent.Json).value.jsonObject))
            assertEquals(doi, document.citationMetadata["citation_doi"])
            assertFalse(document.content.contains(doi))
            var retainedSnapshot: ResearchSnapshot? = null
            val journal = object : ResearchJournal {
                override suspend fun load() = retainedSnapshot
                override suspend fun save(snapshot: ResearchSnapshot) {
                    retainedSnapshot = snapshot
                }
                override fun stopRequested() = false
            }
            val options = DeepResearchSettings(schemaVersion = 3, customSourceTarget = 1, effort = 50, maxPages = 1, maxRounds = 1, concurrency = 1, allowRemoteReaders = true)
            AndroidResearchWorkflow(
                ModelDelegationSettings(maxInputCharacters = 12000, maxOutputTokens = 768, deepResearch = options),
                listOf(ResolvedAgentTool(tool, "private-reader", null, "read_url", "read_url")),
                { prompt, _ ->
                    when {
                        prompt.startsWith("Plan") -> """{"questions":[{"id":"Q1","text":"What was measured?"}],"queries":[]}"""
                        prompt.startsWith("Extract") -> """{"claims":[]}"""
                        else -> """{"missing":[],"queries":[]}"""
                    }
                },
                journal,
                memoryAvailable = { true }
            ).run("Research measured performance at $url", "metadata-workflow")
            val captured = requireNotNull(retainedSnapshot).sources.single()
            assertEquals(doi, captured.doi)
            assertEquals("45678", captured.pmid)
            assertEquals("PMC45678", captured.pmcid)
            assertEquals("doi:$doi", captured.equivalenceGroup)
            assertEquals("unknown", captured.reviewStatus)
            assertFalse(captured.publicCapture)
            assertTrue(captured.metadataProvenance.any { it.startsWith("authorized_citation_metadata:") })
            val original = ResearchSource("S1", url, "Private capture", "Read", body, doi = "10.1234/retained", versionId = "retained-version", publicCapture = false)
            val conflict = enrichResearchSourceMetadata(original, document.content, document.citationMetadata)
            assertEquals(original.versionId, conflict.versionId)
            assertEquals(original.passage, conflict.passage)
            assertEquals(original.doi, conflict.doi)
            assertFalse(conflict.publicCapture)
            assertTrue("identifier_conflict:doi" in conflict.metadataProvenance)
        } finally {
            server.stop(0)
            ResearchConnectivityGate.shared.reset()
        }
    }

    @Test fun realHtmlTextAndPdfReaderResultsReachRouterWithoutIncludeLinks() = runBlocking {
        ResearchConnectivityGate.shared.reset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val pdf = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 10f)
                stream.newLineAtOffset(20f, 700f)
                stream.showText(body)
                stream.endText()
            }
            document.save(pdf)
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val bytes = when (path) {
                "/article.pdf" -> pdf.toByteArray()
                "/article.html" -> "<html><body><article><p>$body</p></article></body></html>".encodeToByteArray()
                else -> body.encodeToByteArray()
            }
            exchange.responseHeaders.add(
                "Content-Type",
                when (path) {
                    "/article.pdf" -> "application/pdf"
                    "/article.html" -> "text/html"
                    else -> "text/plain"
                }
            )
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val tool = ReadUrlTool(dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }, allowAddress = { it.isLoopbackAddress }, documentContext = context)
            for (path in listOf("/article.html", "/article.txt", "/article.pdf")) {
                val url = "http://fixture-reader.example:${server.address.port}$path"
                val direct = tool.execute(
                    "direct-$path",
                    buildJsonObject {
                        put("url", url)
                        put("extractDocument", true)
                    }
                )
                assertFalse(direct.toString(), direct.isError)
                val payload = (direct.content as ToolResultContent.Json).value.jsonObject
                val document = requireNotNull(ResearchDocumentResult.fromJson(payload))
                assertTrue(document.content.contains("no measured difference"))
                assertEquals(emptyList<String>(), document.links)
                assertFalse(document.truncated)
                val paper = AcademicPaper("url:${researchHash(url)}", "Synthetic trial", url, openAccessUrl = url)
                val initial = AcademicEvidence(paper.paperId, "metadata", url, 1, researchHash(""), "", "metadata_only")
                val result = ResearchReaderRouter.fallback(paper, initial, listOf(ResolvedAgentTool(tool, null, null, "read_url", "read_url")), mockk<ResearchSessionStore>(), emptyList(), emptyList()) { true }
                assertEquals(path, "read", result.retrievalStatus)
                assertTrue(result.text.contains("no measured difference"))
                assertFalse(result.structuredFullText)
                assertEquals(researchHash(result.text), result.textHash)
            }
        } finally {
            server.stop(0)
            ResearchConnectivityGate.shared.reset()
        }
    }

    @Test fun forbiddenDocumentIsTypedSourceAccessFailureAndDoesNotBecomeScientificEvidence() = runBlocking {
        ResearchConnectivityGate.shared.reset()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = "Access denied".encodeToByteArray()
            exchange.sendResponseHeaders(403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://fixture-blocked.example:${server.address.port}/article"
            val tool = ReadUrlTool(dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }, allowAddress = { it.isLoopbackAddress })
            val paper = AcademicPaper("url:blocked", "Study", url)
            val initial = AcademicEvidence(paper.paperId, "metadata", url, 1, researchHash(""), "", "metadata_only")
            val result = ResearchReaderRouter.fallback(paper, initial, listOf(ResolvedAgentTool(tool, null, null, "read_url", "read_url")), mockk<ResearchSessionStore>(), emptyList(), emptyList()) { true }
            assertEquals("blocked", result.retrievalStatus)
            assertEquals("SOURCE_ACCESS_BLOCKED", result.failureCode)
            assertEquals("", result.text)
            val state = examinationFailure(ExaminationRetrievalException(result), 0, 1000)
            assertEquals(ExaminationStage.BLOCKED_SOURCE, state.stage)
            assertFalse(state.retryable)
        } finally {
            server.stop(0)
            ResearchConnectivityGate.shared.reset()
        }
    }
}
