package dev.chungjungsoo.gptmobile.data.research

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchDiagnosticPagesTest {
    @Test fun malformedPageDoesNotSuppressSiblingPagesOrTheirEvidence() = withDirectory { directory ->
        val corrupt = page(directory, "source", "{invalid-json")
        val good = page(directory, "source", "{\"title\":\"retained evidence\"}")
        val index = page(directory, "index", JsonArray(listOf(JsonPrimitive(corrupt.name), JsonPrimitive(good.name))).toString())
        val root = session(directory, sourcePages = listOf(index.name))
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        runBlocking { walker(directory, rows, omissions).session(root, 7, "run", "local") }
        assertTrue(omissions.contains("RESEARCH_PAGE_INVALID_JSON"))
        assertTrue(rows.any { it.toString().contains("retained evidence") })
    }

    @Test fun rootChatAndRunAreValidatedBeforeAnyPayloadIsExported() = withDirectory { directory ->
        val root = session(directory, chatId = 8)
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        runBlocking { walker(directory, rows, omissions).session(root, 7, "run", "local") }
        assertTrue(rows.isEmpty())
        assertEquals(listOf("RESEARCH_SESSION_SCOPE_MISMATCH"), omissions)
    }

    @Test fun queryTextThatLooksLikeAReferenceCannotOpenAnotherChatsEvidence() = withDirectory { directory ->
        val other = page(directory, "source", "{\"title\":\"other chat private source\"}")
        val query = page(directory, "index", JsonArray(listOf(JsonPrimitive(other.name))).toString())
        val root = session(directory, queryPages = listOf(query.name))
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        runBlocking { walker(directory, rows, omissions).session(root, 7, "run", "local") }
        assertEquals(2, rows.size)
        assertFalse(rows.toString().contains("other chat private source"))
    }

    @Test fun invalidReferenceAndDigestFailureLeaveLaterChunksReadable() = withDirectory { directory ->
        val damaged = page(directory, "chunk", "initial text").apply { writeText("changed bytes") }
        val good = page(directory, "chunk", "preserved body")
        val source = page(directory, "source", JsonObject(mapOf("bodyChunks" to JsonArray(listOf(JsonPrimitive("../private.chunk"), JsonPrimitive(damaged.name), JsonPrimitive(good.name))))).toString())
        val root = session(directory, sourceRefs = listOf(source.name))
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        runBlocking { walker(directory, rows, omissions).session(root, 7, "run", "local") }
        assertTrue(omissions.contains("RESEARCH_REFERENCE_INVALID"))
        assertTrue(omissions.contains("RESEARCH_PAGE_DIGEST_MISMATCH"))
        assertTrue(rows.any { it.toString().contains("preserved body") })
    }

    @Test fun consumerBudgetIOExceptionEscapesWithoutBeingMisclassifiedAsReadFailure() = withDirectory { directory ->
        val root = session(directory)
        val omissions = mutableListOf<String>()
        val expected = IOException("stage limit")
        val pages = ResearchDiagnosticPages(directory, 4096, {}, { file, limit -> file.inputStream().use { readResearchDiagnosticPage(it, limit) } }, { throw expected }, omissions::add)
        val observed = assertThrows(IOException::class.java) { runBlocking { pages.session(root, 7, "run", "local") } }
        assertTrue(observed === expected)
        assertTrue(omissions.isEmpty())
    }

    @Test fun boundedReaderChecksActualBytesAndRejectsInvalidUtf8() {
        val large = assertThrows(ResearchDiagnosticPageFailure::class.java) {
            readResearchDiagnosticPage(ByteArray(4097) { 'a'.code.toByte() }.inputStream(), 4096)
        }
        assertEquals("RESEARCH_PAGE_BYTE_LIMIT", large.reason)
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            readResearchDiagnosticPage(byteArrayOf(0xc3.toByte(), 0x28).inputStream(), 4096)
        }
    }

    private fun walker(directory: File, rows: MutableList<JsonObject>, omissions: MutableList<String>) = ResearchDiagnosticPages(
        directory,
        16 * 1024,
        {},
        { file, limit -> file.inputStream().use { readResearchDiagnosticPage(it, limit) } },
        { rows.add(it) },
        omissions::add
    )

    private fun session(directory: File, chatId: Int = 7, sourcePages: List<String> = emptyList(), sourceRefs: List<String> = emptyList(), queryPages: List<String> = emptyList()): File = File(directory, hash("run") + ".json").apply {
        writeText(
            JsonObject(
                mapOf(
                    "runId" to JsonPrimitive("run"),
                    "chatId" to JsonPrimitive(chatId),
                    "authority" to JsonPrimitive("local"),
                    "sourcePages" to JsonArray(sourcePages.map(::JsonPrimitive)),
                    "sourceRefs" to JsonArray(sourceRefs.map(::JsonPrimitive)),
                    "queryPages" to JsonArray(queryPages.map(::JsonPrimitive))
                )
            ).toString()
        )
    }

    private fun page(directory: File, extension: String, text: String): File = File(directory, hash(text) + ".$extension").apply { writeText(text) }
    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun withDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("research-diagnostic-pages").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
