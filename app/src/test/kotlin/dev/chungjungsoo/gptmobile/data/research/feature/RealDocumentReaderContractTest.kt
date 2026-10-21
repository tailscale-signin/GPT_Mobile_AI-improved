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
import dev.chungjungsoo.gptmobile.data.agent.tool.ReadUrlTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.research.ResearchDocumentResult
import dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicEvidence
import dev.chungjungsoo.gptmobile.data.research.academic.AcademicPaper
import dev.chungjungsoo.gptmobile.data.research.academic.ResearchConnectivityGate
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

    @Test fun realHtmlTextAndPdfReaderResultsReachRouterWithoutIncludeLinks() = runBlocking {
        ResearchConnectivityGate.shared.reset()
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val pdf = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 10f); stream.newLineAtOffset(20f, 700f); stream.showText(body); stream.endText()
            }
            document.save(pdf)
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val bytes = when (path) { "/article.pdf" -> pdf.toByteArray(); "/article.html" -> "<html><body><article><p>$body</p></article></body></html>".encodeToByteArray(); else -> body.encodeToByteArray() }
            exchange.responseHeaders.add("Content-Type", when (path) { "/article.pdf" -> "application/pdf"; "/article.html" -> "text/html"; else -> "text/plain" })
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val tool = ReadUrlTool(dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) }, allowAddress = { it.isLoopbackAddress }, documentContext = context)
            for (path in listOf("/article.html", "/article.txt", "/article.pdf")) {
                val url = "http://fixture-reader.example:${server.address.port}$path"
                val direct = tool.execute("direct-$path", buildJsonObject { put("url", url); put("extractDocument", true) })
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
        } finally { server.stop(0); ResearchConnectivityGate.shared.reset() }
    }

    @Test fun forbiddenDocumentIsTypedSourceAccessFailureAndDoesNotBecomeScientificEvidence() = runBlocking {
        ResearchConnectivityGate.shared.reset()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = "Access denied".encodeToByteArray()
            exchange.sendResponseHeaders(403, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
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
        } finally { server.stop(0); ResearchConnectivityGate.shared.reset() }
    }
}
