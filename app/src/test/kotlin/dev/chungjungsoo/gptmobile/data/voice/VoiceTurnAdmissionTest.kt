package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceTurnAdmissionTest {
    @Test fun restoredAdmissionFollowsAcceptedRunInsteadOfNewestAssistant() {
        val request = VoiceTurnAdmission("request", 7, "private", VoiceAdmissionStage.ACCEPTED, 11, listOf("accepted"))
        val restored = Json.decodeFromString<VoiceTurnAdmission>(Json.encodeToString(VoiceTurnAdmission.serializer(), request))
        val run = AgentRun("accepted", 7, 11, 12, "private", "LLAMA", "model")
        val messages = listOf(
            MessageV2(id = 12, chatId = 7, platformType = "private", currentRunId = "accepted", content = "This turn"),
            MessageV2(id = 99, chatId = 7, platformType = "private", currentRunId = "other", content = "Unrelated turn")
        )
        assertEquals("This turn", restored.reply(messages, mapOf("accepted" to run))?.second?.content)
        assertNull(restored.reply(messages, mapOf("accepted" to run.copy(userMessageId = 22))))
        assertNull(restored.reply(messages.map { it.copy(currentRunId = "replacement") }, mapOf("accepted" to run)))
        assertNull(restored.copy(stage = VoiceAdmissionStage.CHECKING).reply(messages, mapOf("accepted" to run)))
    }
}
