package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

/** One durable terminal publication, read with its message under the same database transaction. */
data class VoiceTerminalReply(val message: MessageV2, val run: AgentRun)
