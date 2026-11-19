package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.dto.google.common.Content
import dev.chungjungsoo.gptmobile.data.dto.google.common.Part
import dev.chungjungsoo.gptmobile.data.dto.google.response.Candidate
import dev.chungjungsoo.gptmobile.data.dto.google.response.GenerateContentResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiImageOutputTest {
    private fun frame(vararg parts: Part, reason: String? = null) = GenerateContentResponse(
        candidates = listOf(Candidate(content = Content(parts = parts.toList()), finishReason = reason))
    )

    @Test fun requestsImageOutputOnlyForExplicitImageModelProfiles() {
        assertEquals(listOf("TEXT", "IMAGE"), geminiResponseModalities("models/gemini-2.5-flash-image"))
        assertNull(geminiResponseModalities("gemini-2.5-flash"))
        assertNull(geminiResponseModalities("my-image-proxy"))
    }

    @Test fun finalCandidateWritesEachPublicImageOnce() {
        val mapper = GeminiImageOutput()
        val part = Part.inlineData("image/png", "ZA==")
        val saves = mutableListOf<String>()
        val save: (dev.chungjungsoo.gptmobile.data.dto.google.common.InlineData) -> String? = {
            saves += it.data
            "gptmobile://media/fixture.png"
        }
        assertNull(mapper.accept(frame(part.copy(thought = true), part, part), save))
        assertTrue(saves.isEmpty())
        val answer = mapper.accept(frame(reason = "STOP"), save).orEmpty()
        assertTrue(answer.contains("[Open image 1]"))
        assertTrue(!answer.contains("ZA=="))
        assertEquals(listOf("ZA=="), saves)
        assertNull(mapper.accept(frame(part, reason = "STOP"), save))
        assertEquals(1, saves.size)
    }

    @Test fun blockedOrPrivateImagesNeverReachStorage() {
        val image = Part.inlineData("image/png", "ZA==")
        val mapper = GeminiImageOutput()
        assertNull(mapper.accept(frame(image)) { error("premature") })
        assertNull(mapper.accept(frame(reason = "IMAGE_SAFETY")) { error("blocked") })
        assertNull(mapper.accept(frame(reason = "STOP")) { error("already blocked") })
        assertNull(GeminiImageOutput().accept(frame(image.copy(thought = true), reason = "STOP")) { error("private") })
    }

    @Test fun decodesActualWireFieldNamesAndRejectsUnsupportedTypes() {
        val wire = """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"image/svg+xml","data":"ignored"}}]},"finishReason":"STOP"}]}"""
        val response = Json.decodeFromString(GenerateContentResponse.serializer(), wire)
        val notice = GeminiImageOutput().accept(response) { error("unsupported MIME type reached storage") }
        assertTrue(notice.orEmpty().contains("limits"))
    }
}
