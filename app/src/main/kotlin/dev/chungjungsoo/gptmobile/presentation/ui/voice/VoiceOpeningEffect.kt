package dev.chungjungsoo.gptmobile.presentation.ui.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionStage
import dev.chungjungsoo.gptmobile.data.voice.VoiceOpeningPlan
import dev.chungjungsoo.gptmobile.data.voice.VoiceTurnAdmission
import dev.chungjungsoo.gptmobile.data.voice.VoiceTurnOpenings
import dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModel
import kotlinx.coroutines.delay

/** Both dictated and typed Voice turns must own the visible, still-empty assistant message. */
internal fun voiceOpeningPlan(
    admission: VoiceTurnAdmission?,
    transcript: String,
    typed: Boolean,
    room: Int,
    profile: String?,
    messages: ChatViewModel.GroupedMessages,
    runs: Map<String, AgentRun>,
    events: Map<String, List<ToolEvent>>,
    language: String
): VoiceOpeningPlan? {
    val user = messages.userMessages.lastOrNull()?.takeIf { it.chatId == room && it.platformType == null } ?: return null
    if (user.timeline.any { it.voiceResponsePolicy?.translationOnly == true }) return null
    val run = if (typed) {
        if (user.timeline.none { it.voiceResponsePolicy != null }) return null
        runs.values.singleOrNull { it.chatId == room && it.userMessageId == user.id && it.profileUid == profile && it.status == AgentRunStatus.RUNNING }
    } else {
        if (admission?.stage != VoiceAdmissionStage.ACCEPTED || admission.chatId != room || admission.profileUid != profile || admission.userMessageId != user.id || user.content != transcript) return null
        admission.run(runs)
    } ?: return null
    val assistant = messages.assistantMessages.asSequence().flatten().firstOrNull { it.id == run.assistantMessageId } ?: return null
    if (assistant.chatId != room || assistant.currentRunId != run.runId || assistant.ownershipFence != run.ownershipFence || ConversationSubject.withoutMetadata(assistant.content).isNotBlank()) return null
    return VoiceTurnOpenings.plan(user.content, run, language, events[run.runId].orEmpty())
}

/** The grace timer is independent of answer delivery. A real answer or navigation cancels it. */
@Composable
internal fun rememberDueVoiceOpening(plan: VoiceOpeningPlan?): VoiceOpeningPlan? {
    var due by remember(plan) { mutableStateOf(false) }
    LaunchedEffect(plan) {
        if (plan != null) {
            delay(plan.delayMs)
            due = true
        }
    }
    return plan.takeIf { due }
}
