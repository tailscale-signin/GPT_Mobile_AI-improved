package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageV2Dao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ModelUsageStat(
    val profileUid: String,
    val model: String,
    val provider: String,
    val runs: Int,
    val estimatedOutputTokens: Int
)

data class ToolUsageStat(
    val name: String,
    val calls: Int
)

data class DebugAnalyticsState(
    val recentRuns: List<AgentRun> = emptyList(),
    val recentToolEvents: List<ToolEvent> = emptyList(),
    val completedRuns: Int = 0,
    val failedRuns: Int = 0,
    val activeRuns: Int = 0,
    val completedToolCalls: Int = 0,
    val failedToolCalls: Int = 0,
    val averageRunDurationMs: Long? = null,
    val averageToolDurationMs: Long? = null,
    val estimatedGeneratedTokens: Int = 0,
    val successRatePercent: Int = 0,
    val modelUsage: List<ModelUsageStat> = emptyList(),
    val topTools: List<ToolUsageStat> = emptyList()
)

@HiltViewModel
class DebugDiagnosticsViewModel @Inject constructor(
    agentRunDao: AgentRunDao,
    agentPersistenceDao: AgentPersistenceDao,
    messageV2Dao: MessageV2Dao
) : ViewModel() {
    val analytics: StateFlow<DebugAnalyticsState> = combine(
        agentRunDao.observeRecent(250),
        agentPersistenceDao.observeRecentToolEvents(500),
        messageV2Dao.observeRecentAssistantMessages(750)
    ) { runs, tools, messages ->
        val messageById = messages.associateBy { it.id }
        val usage = runs.groupBy { Triple(it.profileUid, it.modelSnapshot, it.providerSnapshot) }
            .map { (key, groupedRuns) ->
                val tokenEstimate = groupedRuns.sumOf { run ->
                    messageById[run.assistantMessageId]?.content?.length?.let { (it + 3) / 4 } ?: 0
                }
                ModelUsageStat(
                    profileUid = key.first,
                    model = key.second.ifBlank { "Unknown model" },
                    provider = key.third,
                    runs = groupedRuns.size,
                    estimatedOutputTokens = tokenEstimate
                )
            }
            .sortedWith(compareByDescending<ModelUsageStat> { it.estimatedOutputTokens }.thenByDescending { it.runs })

        val terminalRuns = runs.count { it.status != AgentRunStatus.RUNNING && it.status != AgentRunStatus.QUEUED }
        val successfulRuns = runs.count { it.status == AgentRunStatus.COMPLETED }

        DebugAnalyticsState(
            recentRuns = runs,
            recentToolEvents = tools,
            completedRuns = runs.count { it.status == AgentRunStatus.COMPLETED },
            failedRuns = runs.count { it.status == AgentRunStatus.FAILED },
            activeRuns = runs.count { it.status == AgentRunStatus.RUNNING || it.status == AgentRunStatus.QUEUED },
            completedToolCalls = tools.count { it.status == ToolEventStatus.COMPLETED },
            failedToolCalls = tools.count { it.status == ToolEventStatus.FAILED },
            averageRunDurationMs = runs.mapNotNull { run ->
                val start = run.startedAt ?: return@mapNotNull null
                val end = run.completedAt ?: return@mapNotNull null
                ((end - start) * 1000L).coerceAtLeast(0L)
            }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            averageToolDurationMs = tools.mapNotNull { event ->
                val start = event.startedAt ?: return@mapNotNull null
                val end = event.completedAt ?: return@mapNotNull null
                ((end - start) * 1000L).coerceAtLeast(0L)
            }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            estimatedGeneratedTokens = usage.sumOf { it.estimatedOutputTokens },
            successRatePercent = if (terminalRuns > 0) (successfulRuns * 100 / terminalRuns) else 0,
            modelUsage = usage,
            topTools = tools.groupingBy { it.modelToolName }.eachCount()
                .entries.sortedByDescending { it.value }.take(8)
                .map { ToolUsageStat(it.key, it.value) }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DebugAnalyticsState())
}
