package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class UsageStatistics(
    val runs: Int = 0,
    val conversations: Int = 0,
    val profiles: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val canceled: Int = 0,
    val inputTokens: Long = 0,
    val generatedTokens: Long = 0,
    val reportedRuns: Int = 0,
    val averageSeconds: Double? = null,
    val modelTokens: List<Pair<String, Long>> = emptyList(),
    val profileTokens: List<Pair<String, Long>> = emptyList(),
    val modelRuns: List<Pair<String, Long>> = emptyList(),
    val dailyTokens: List<Pair<LocalDate, Long>> = emptyList(),
    val toolCalls: Int = 0,
    val failedTools: Int = 0,
    val days: Int = 30
)

internal fun calculateUsageStatistics(runs: List<AgentRun>, profileNames: Map<String, String>, days: Int, today: LocalDate = LocalDate.now()): UsageStatistics {
    val zone = ZoneId.systemDefault()
    fun day(run: AgentRun): LocalDate = Instant.ofEpochSecond(run.createdAt).atZone(zone).toLocalDate()
    val filtered = runs.filter { days == 0 || !day(it).isBefore(today.minusDays(days - 1L)) }
    fun totals(key: (AgentRun) -> String): List<Pair<String, Long>> = filtered.filter { it.outputTokens != null }
        .groupBy(key).map { (name, items) -> name to items.sumOf { it.outputTokens!!.coerceAtLeast(0).toLong() } }
        .sortedByDescending { it.second }
    val daily = filtered.groupBy(::day).mapValues { (_, items) -> items.sumOf { it.outputTokens?.coerceAtLeast(0)?.toLong() ?: 0L } }
    val chartDays = if (days == 0) 30 else days
    val durations = filtered.mapNotNull { run ->
        val start = run.startedAt ?: return@mapNotNull null
        val end = run.completedAt ?: return@mapNotNull null
        (end - start).coerceAtLeast(0L)
    }
    return UsageStatistics(
        runs = filtered.size,
        conversations = filtered.map { it.chatId }.distinct().size,
        profiles = filtered.map { it.profileUid }.distinct().size,
        completed = filtered.count { it.status == AgentRunStatus.COMPLETED },
        failed = filtered.count { it.status == AgentRunStatus.FAILED || it.status == AgentRunStatus.INTERRUPTED },
        canceled = filtered.count { it.status == AgentRunStatus.CANCELED },
        inputTokens = filtered.sumOf { it.inputTokens?.coerceAtLeast(0)?.toLong() ?: 0 },
        generatedTokens = filtered.sumOf { it.outputTokens?.coerceAtLeast(0)?.toLong() ?: 0 },
        reportedRuns = filtered.count { it.outputTokens != null },
        averageSeconds = durations.takeIf { it.isNotEmpty() }?.average(),
        modelTokens = totals { it.modelSnapshot.ifBlank { "Unknown model" } },
        profileTokens = totals { profileNames[it.profileUid] ?: "Deleted profile (${it.profileUid.take(8)})" },
        modelRuns = filtered.groupingBy { it.modelSnapshot.ifBlank { "Unknown model" } }.eachCount().map { it.key to it.value.toLong() }.sortedByDescending { it.second },
        dailyTokens = (chartDays - 1 downTo 0).map { today.minusDays(it.toLong()).let { date -> date to (daily[date] ?: 0) } },
        days = days
    )
}

@HiltViewModel
class UsageStatisticsViewModel @Inject constructor(
    runDao: AgentRunDao,
    persistenceDao: AgentPersistenceDao,
    settings: SettingRepository
) : ViewModel() {
    private val days = MutableStateFlow(30)
    fun selectRange(value: Int) {
        days.value = value
    }

    val statistics = combine(
        runDao.observeRecent(10_000),
        persistenceDao.observeRecentToolEvents(10_000),
        settings.observePlatformV2s(),
        days
    ) { runs, tools, profiles, range ->
        val cutoff = if (range == 0) 0L else LocalDate.now().minusDays(range - 1L).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val selectedTools = tools.filter { (it.startedAt ?: 0) >= cutoff }
        calculateUsageStatistics(runs, profiles.associate { it.uid to it.name }, range).copy(
            toolCalls = selectedTools.size,
            failedTools = selectedTools.count { it.status == ToolEventStatus.FAILED }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsageStatistics())
}
