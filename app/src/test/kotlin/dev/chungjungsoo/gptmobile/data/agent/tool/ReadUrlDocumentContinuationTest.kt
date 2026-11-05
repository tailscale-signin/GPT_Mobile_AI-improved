package dev.chungjungsoo.gptmobile.data.agent.tool

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import dev.chungjungsoo.gptmobile.data.agent.ToolOutputAllowance
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.research.ResearchDocumentResult
import dev.chungjungsoo.gptmobile.util.DocumentTextExtractor
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadUrlDocumentContinuationTest {
    @get:Rule val files = TemporaryFolder()
    private var server: HttpServer? = null
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @After fun stopServer() {
        server?.stop(0)
    }

    @Test fun continuationDeliversTheWholePageRangeBeforeAdvancingToLaterPages() = runBlocking {
        val bytes = pdf("Verified evidence")
        val url = serve { bytes }
        val reader = reader()
        val file = files.newFile("expected.pdf").apply { writeBytes(bytes) }
        val expected = mapOf(
            1 to normalized(DocumentTextExtractor.extract(context, file, "application/pdf", pageStart = 1, pageLimit = 2).text),
            3 to normalized(DocumentTextExtractor.extract(context, file, "application/pdf", pageStart = 3, pageLimit = 2).text)
        )
        val delivered = linkedMapOf<Int, StringBuilder>()
        var arguments: JsonObject? = buildJsonObject {
            put("url", url)
            put("extractDocument", true)
            put("documentPageLimit", 2)
        }
        var requests = 0
        var sourceSha: String? = null
        while (arguments != null && requests < 20) {
            val current = requireNotNull(arguments)
            val rangeStart = current["documentPageStart"]?.jsonPrimitive?.intOrNull ?: 1
            val offset = current["documentTextOffset"]?.jsonPrimitive?.intOrNull ?: 0
            val result = reader.execute("excerpt-${requests++}", current)
            assertFalse(result.content.toString(), result.isError)
            val page = requireNotNull(ResearchDocumentResult.fromJson((result.content as ToolResultContent.Json).value.jsonObject))
            val text = delivered.getOrPut(rangeStart) { StringBuilder() }
            assertEquals(text.length, offset)
            assertEquals(offset, page.textStartOffset)
            assertEquals(offset + page.content.length, page.textEndOffset)
            assertEquals(expected.getValue(rangeStart).length, page.extractedTextCharacters)
            assertEquals(emptyList<String>(), page.bodyChunks) // Continuation works without a private chunk store.
            assertTrue(page.content.toByteArray().size <= 256)
            assertTrue(page.truncated) // Each result covers only part of the document.
            assertEquals("partially_read", page.status)
            assertNotNull(page.sourceSha256)
            if (sourceSha == null) sourceSha = page.sourceSha256
            assertEquals(sourceSha, page.sourceSha256)
            text.append(page.content)
            arguments = page.nextReadArguments
            if (arguments != null) {
                assertEquals(sourceSha, requireNotNull(arguments)["expectedDocumentSha256"]?.jsonPrimitive?.content)
                val nextPage = requireNotNull(arguments)["documentPageStart"]?.jsonPrimitive?.intOrNull
                if (nextPage != rangeStart) assertEquals(expected.getValue(rangeStart), text.toString())
            }
        }
        assertEquals(null, arguments)
        assertTrue("Fixture must exercise an excerpt continuation", requests > 2)
        assertEquals(expected, delivered.mapValues { it.value.toString() })
    }

    @Test fun continuationRejectsChangedDocumentBytesInsteadOfCombiningVersions() = runBlocking {
        var bytes = pdf("First revision")
        val url = serve { bytes }
        val reader = reader()
        val first = reader.execute("first", buildJsonObject { put("url", url) })
        assertFalse(first.isError)
        val page = requireNotNull(ResearchDocumentResult.fromJson((first.content as ToolResultContent.Json).value.jsonObject))
        val next = requireNotNull(page.nextReadArguments)
        bytes = pdf("Second revision")
        val changed = reader.execute("next", next)
        assertTrue(changed.isError)
        assertTrue(changed.dispatched)
        assertEquals("SOURCE_CHANGED", changed.errorCode)
        assertTrue(changed.content.toString().contains("restart reading"))
    }

    @Test fun modelOutputAllowancePreservesTheDeliveredTextCursorAndSourceIdentity() = runBlocking {
        val bytes = pdf("Budgeted evidence", lines = 25)
        val url = serve { bytes }
        val result = withContext(ToolOutputAllowance(4096)) {
            reader(outputLimit = 64 * 1024).execute("budget", buildJsonObject { put("url", url) })
        }
        assertFalse(result.isError)
        val payload = (result.content as ToolResultContent.Json).value.jsonObject
        assertTrue(payload.toString().toByteArray().size <= 4096)
        assertEquals(result.content, ToolResultEnvelope.compact(result.content, 4096))
        val page = requireNotNull(ResearchDocumentResult.fromJson(payload))
        assertTrue(page.content.isNotEmpty())
        assertTrue(page.truncated)
        val next = requireNotNull(page.nextReadArguments)
        assertEquals(page.content.length, next["documentTextOffset"]?.jsonPrimitive?.intOrNull)
        assertEquals(page.sourceSha256, next["expectedDocumentSha256"]?.jsonPrimitive?.content)
    }

    @Test fun anAllowanceTooSmallForContinuationMetadataIsAnExplicitBudgetFailure() = runBlocking {
        val bytes = pdf("Retained evidence")
        val url = serve { bytes }
        val result = withContext(ToolOutputAllowance(512)) {
            reader().execute("too-small", buildJsonObject { put("url", url) })
        }
        assertTrue(result.isError)
        assertTrue(result.outputBudgetExhausted)
        assertTrue(result.dispatched)
        assertEquals("BUDGET_EXHAUSTED", result.errorCode)
        assertTrue((result.content as ToolResultContent.Text).text.toByteArray().size <= 512)
        val retained = requireNotNull(ResearchDocumentResult.fromJson((result.retainedContent as ToolResultContent.Json).value.jsonObject))
        assertTrue(retained.content.contains("Retained evidence"))
        assertNotNull(retained.sourceSha256)
    }

    @Test fun aLargeDocumentHandoffPreservesTheRequestedRangeAndSourcePin() = runBlocking {
        // The inline path must hand off without trying to parse this oversized prefix.
        val bytes = pdf("Source") + ByteArray(1024 * 1024)
        val url = serve { bytes }
        val expectedHash = "a".repeat(64)
        val result = reader().execute(
            "large",
            buildJsonObject {
                put("url", url)
                put("documentPageStart", 2)
                put("documentPageLimit", 1)
                put("documentTextOffset", 4)
                put("expectedDocumentSha256", expectedHash)
            }
        )
        assertTrue(result.isError)
        assertEquals("DOCUMENT_EXTRACTION_REQUIRED", result.errorCode)
        val next = (result.content as ToolResultContent.Json).value.jsonObject.getValue("arguments").jsonObject
        assertEquals(2, next["documentPageStart"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, next["documentPageLimit"]?.jsonPrimitive?.intOrNull)
        assertEquals(4, next["documentTextOffset"]?.jsonPrimitive?.intOrNull)
        assertEquals(expectedHash, next["expectedDocumentSha256"]?.jsonPrimitive?.content)
    }

    private fun reader(outputLimit: Int = 256) = ReadUrlTool(
        dns = Dns { listOf(InetAddress.getByName("127.0.0.1")) },
        allowAddress = { it.isLoopbackAddress },
        documentContext = context,
        outputLimitBytes = outputLimit
    )

    private fun serve(body: () -> ByteArray): String {
        val instance = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        instance.createContext("/document.pdf") { exchange ->
            val bytes = body()
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        instance.start()
        server = instance
        return "http://document.test:${instance.address.port}/document.pdf"
    }

    private fun pdf(prefix: String, lines: Int = 7): ByteArray {
        PDFBoxResourceLoader.init(context)
        return PDDocument().use { document ->
            for (pageNumber in 1..3) {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 10f)
                    stream.setLeading(15f)
                    stream.newLineAtOffset(30f, 700f)
                    repeat(lines) { line ->
                        stream.showText("$prefix page $pageNumber line $line contains the complete source evidence.")
                        stream.newLine()
                    }
                    stream.endText()
                }
            }
            ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
    }

    private fun normalized(text: String) = text.replace(Regex("\\s+"), " ").trim()
}
