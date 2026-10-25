package dev.chungjungsoo.gptmobile.presentation.ui.benchmark

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.agent.CombinedDocuments
import dev.chungjungsoo.gptmobile.data.agent.CombinedPassageLink
import dev.chungjungsoo.gptmobile.data.agent.CombinedPassageRecord
import dev.chungjungsoo.gptmobile.data.agent.CombinedQualityReport
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2Migrations
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.researchHash
import dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedOriginalSourceDialog
import dev.chungjungsoo.gptmobile.presentation.ui.chat.CombinedWordingPresentation
import dev.chungjungsoo.gptmobile.presentation.ui.chat.OpponentChatBubble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Seeds synthetic data once, then exercises the production saved-document loader and renderer. */
@Composable
internal fun SavedCombinedDocumentFixture(profiles: Int, characters: Int) {
    require(profiles in setOf(10, 50, 100) && characters in setOf(100_000, 1_000_000))
    val context = LocalContext.current
    val database = remember(profiles, characters) {
        Room.databaseBuilder(context.applicationContext, ChatDatabaseV2::class.java, "combined-render-v1-$profiles-$characters")
            .addMigrations(*ChatDatabaseV2Migrations.ALL_MIGRATIONS)
            .build()
    }
    DisposableEffect(database) { onDispose { database.close() } }
    val saved by produceState<MessageV2?>(null, database) {
        value = withContext(Dispatchers.IO) { seedSavedDocument(database, profiles, characters) }
    }
    var open by remember { mutableStateOf(false) }
    var contributor by remember { mutableStateOf(false) }
    val message = saved
    Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        if (message == null) {
            Text("Preparing synthetic saved answer")
        } else if (!open) {
            Text("Saved synthetic answer: $profiles profiles, $characters characters", Modifier.testTag("benchmark_saved_ready"))
            Button(onClick = { open = true }, modifier = Modifier.testTag("benchmark_open_final")) { Text("Open saved answer") }
        } else {
            val wording = remember(message, database) {
                CombinedWordingPresentation(
                    message.combinedSources,
                    message.combinedSources.mapIndexed { index, source -> source.platformUid to Color.hsv(index * 360f / profiles, 0.7f, 0.7f) }.toMap(),
                    loadSourceBlocks = { source, first, count -> CombinedDocuments.sourceBlocks(database.messageDao(), requireNotNull(message.combinedManifest), source, first, count) }
                )
            }
            Row {
                Button(onClick = { open = false }, modifier = Modifier.testTag("benchmark_close_final")) { Text("Back") }
                Button(onClick = { contributor = false }, modifier = Modifier.testTag("benchmark_final_tab")) { Text("Final answer") }
                Button(onClick = { contributor = true }, modifier = Modifier.testTag("benchmark_contributor_tab")) { Text("Contributor") }
            }
            if (contributor) {
                CombinedOriginalSourceDialog(
                    passage = "Synthetic contributor evidence",
                    owners = setOf(message.combinedSources.first().platformUid),
                    wording = wording,
                    onDismiss = { contributor = false }
                )
            } else {
                OpponentChatBubble(
                    canRetry = false,
                    isLoading = false,
                    text = message.content,
                    timeline = message.timeline,
                    contentIdentity = message.id,
                    showExecutionTrace = false,
                    combinedWording = wording,
                    combinedManifest = requireNotNull(message.combinedManifest),
                    loadCombinedBlocks = { reference, first, count -> CombinedDocuments.blocks(database.messageDao(), reference, first, count) }
                )
            }
        }
    }
}

private suspend fun seedSavedDocument(database: ChatDatabaseV2, profileCount: Int, characters: Int): MessageV2 {
    database.messageDao().rawMessage(2)?.takeIf { it.combinedManifest != null }?.let { return it }
    val user = MessageV2(id = 1, chatId = 1, platformType = null, content = "Show every synthetic observation.")
    val runId = "combined-synthesis:benchmark:$profileCount:$characters"
    database.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Saved document benchmark"))
    database.messageDao().addMessages(user, MessageV2(id = 2, chatId = 1, platformType = "profile-0", content = "", currentRunId = runId, ownershipFence = 1))
    database.agentRunDao().upsert(AgentRun(runId, 1, 1, 2, "profile-0", "CUSTOM", "synthetic", "RUNNING", ownershipFence = 1))
    val body = StringBuilder(characters + profileCount * 300)
    val sources = mutableListOf<CombinedModelResponse>()
    val passages = mutableListOf<CombinedPassageRecord>()
    repeat(profileCount) { index ->
        val uid = "profile-$index"
        val paragraph = "Profile $index retained **synthetic evidence** with café, e\u0301, and 🦋. [Reference](https://example.com).\n\n"
        val source = "## Profile $index\n\n" + paragraph.repeat((characters / profileCount / paragraph.length).coerceAtLeast(1)) + "| Item | Value |\n| --- | --- |\n| Synthetic item $index | $index |\n\n"
        val start = body.length
        body.append(source)
        val digest = researchHash(source)
        sources += CombinedModelResponse(uid, "Profile $index", content = source, sourceDigest = digest)
        val link = CombinedPassageLink(start, body.length, digest, setOf(uid), setOf("claim-$index"))
        passages += CombinedPassageRecord(start, body.length, digest, setOf(uid), origin = "LOCAL_CLAIM_MAPPING_V1", linkVersion = 1, links = listOf(link))
    }
    val text = body.toString()
    val report = CombinedQualityReport(answerDigest = researchHash(text), complete = true, status = "COMPLETED", passages = passages, revisionId = "synthetic-revision", deliveredScopeStatus = "COMPLETED")
    val answer = MessageV2(
        id = 2,
        chatId = 1,
        platformType = "profile-0",
        content = text,
        currentRunId = runId,
        ownershipFence = 1,
        combinedSources = sources,
        timeline = listOf(AssistantTimelineItem(AssistantTimelineItemType.TEXT, text), AssistantTimelineItem(AssistantTimelineItemType.NOTICE, combinedQuality = report))
    )
    check(database.agentPersistenceDao().finishRunningWithMessage(answer, runId, "COMPLETED", 1, null))
    return requireNotNull(database.messageDao().rawMessage(2)).also { require(it.combinedManifest != null && it.content.length <= 256) }
}
