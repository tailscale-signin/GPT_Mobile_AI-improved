package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Choice
import dev.chungjungsoo.gptmobile.data.dto.openai.response.Delta
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FreeTextBlocksTest {
    @Test fun serializedBlocksSurviveEveryPossibleChunkBoundary() = runBlocking {
        val wire = """[{"type":"text","text":"Hello "},{"type":"text","text":"Toronto!"}]"""
        for (split in 0..wire.length) {
            val chunks = listOf(
                ChatCompletionChunk(choices = listOf(Choice(delta = Delta(content = wire.take(split))))),
                ChatCompletionChunk(choices = listOf(Choice(delta = Delta(content = wire.drop(split)), finishReason = "stop")))
            ).asFlow().normalizeFreeTextBlocks(FreeAiProvider.VIREONIX).toList()
            assertEquals("Hello Toronto!", chunks.flatMap { it.choices.orEmpty() }.joinToString("") { it.effectiveDelta.content.orEmpty() })
            assertEquals("stop", chunks.last().choices!!.single().finishReason)
        }
    }

    @Test fun plainTextAndUnrelatedJsonArePreserved() {
        for (text in listOf("Hello!", "[1,2,3]", "[{\"type\":\"code\",\"text\":\"example\"}]", "[{\"type\":\"text\",\"text\":\"incomplete")) {
            val buffer = FreeTextBlockBuffer()
            assertEquals(text, buffer.append(text) + buffer.finish())
        }
    }

    @Test fun otherProvidersKeepIntentionalJson() = runBlocking {
        val chunk = ChatCompletionChunk(choices = listOf(Choice(delta = Delta(content = """[{"type":"text","text":"example"}]"""))))
        assertEquals(listOf(chunk), listOf(chunk).asFlow().normalizeFreeTextBlocks(FreeAiProvider.KILO).toList())
    }
}
