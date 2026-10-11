package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.dao.AgentRunDao
import dev.chungjungsoo.gptmobile.data.database.dao.RunOutputLength
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
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

data class ProfileModelUsage(
    val profileUid: String,
    val profileName: String,
    val provider: String,
    val model: String,
    val runs: Int,
    val reportedTokens: Long,
    val estimatedTokens: Long
)

data class ToolUsage(val name: String, val calls: Int, val failures: Int)

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
    val successPercent: Double? = null,
    val estimatedTokens: Long = 0,
    val estimatedRuns: Int = 0,
    val profileModelUsage: List<ProfileModelUsage> = emptyList(),
    val toolUsage: List<ToolUsage> = emptyList(),
    val performance: List<ModelPerformance> = emptyList(),
    val days: Int = 30,
    val profileNames: Map<String, String> = emptyMap(),
    val profilePerformance: List<ProfilePerformance> = emptyList(),
    val selectedProfile: String? = null,
    val selectedProvider: String? = null,
    val availableProfiles: Map<String, String> = emptyMap(),
    val availableProviders: List<String> = emptyList(),
    val recordedCosts: Map<String, Long> = emptyMap(),
    val unpricedRequests: Int = 0
)

internal fun calculateUsageStatistics(
    runs: List<AgentRun>,
    profileNames: Map<String, String>,
    days: Int,
    today: LocalDate = LocalDate.now(),
    outputLengths: List<RunOutputLength> = emptyList(),
    tools: List<ToolEvent> = emptyList()
): UsageStatistics {
    require(days in setOf(0, 7, 30))
    val zone = ZoneId.systemDefault()
    fun day(run: AgentRun): LocalDate = Instant.ofEpochSecond(run.createdAt).atZone(zone).toLocalDate()
    fun inRange(date: LocalDate) = !date.isAfter(today) && (days == 0 || !date.isBefore(today.minusDays(days - 1L)))
    val filtered = runs.filter { inRange(day(it)) }
    val lengths = outputLengths.associate { it.runId to it.characters.coerceAtLeast(0) }
    fun estimate(run: AgentRun): Long = if (run.outputTokens == null && run.status == AgentRunStatus.COMPLETED) {
        val length = lengths[run.runId] ?: 0L
        length / 4 + if (length % 4 > 0) 1 else 0
    } else {
        0L
    }
    val completed = filtered.count { it.status == AgentRunStatus.COMPLETED }
    val failed = filtered.count { it.status == AgentRunStatus.FAILED || it.status == AgentRunStatus.INTERRUPTED }
    val selectedTools = tools.filter { event ->
        event.startedAt?.let { inRange(Instant.ofEpochSecond(it).atZone(zone).toLocalDate()) } ?: (days == 0)
    }
    val toolUsage = selectedTools.groupBy { it.connectionUidSnapshot to it.modelToolName.ifBlank { it.toolName } }.values.map { events ->
        val event = events.first()
        val name = listOfNotNull(event.connectionNameSnapshot?.takeIf { it.isNotBlank() }, event.modelToolName.ifBlank { event.toolName }).joinToString(" · ")
        ToolUsage(name, events.size, events.count { it.status == ToolEventStatus.FAILED || it.isError })
    }.sortedWith(compareByDescending<ToolUsage> { it.calls }.thenBy { it.name })
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
        completed = completed,
        failed = failed,
        canceled = filtered.count { it.status == AgentRunStatus.CANCELED },
        inputTokens = filtered.sumOf { it.inputTokens?.coerceAtLeast(0)?.toLong() ?: 0 },
        generatedTokens = filtered.sumOf { it.outputTokens?.coerceAtLeast(0)?.toLong() ?: 0 },
        reportedRuns = filtered.count { it.outputTokens != null },
        averageSeconds = durations.takeIf { it.isNotEmpty() }?.average(),
        modelTokens = totals { it.modelSnapshot.ifBlank { "Unknown model" } },
        profileTokens = totals { profileNames[it.profileUid] ?: "Deleted profile (${it.profileUid.take(8)})" },
        modelRuns = filtered.groupingBy { it.modelSnapshot.ifBlank { "Unknown model" } }.eachCount().map { it.key to it.value.toLong() }.sortedByDescending { it.second },
        dailyTokens = (chartDays - 1 downTo 0).map { today.minusDays(it.toLong()).let { date -> date to (daily[date] ?: 0) } },
        successPercent = if (completed + failed > 0) 100.0 * completed / (completed + failed) else null,
        estimatedTokens = filtered.sumOf(::estimate),
        estimatedRuns = filtered.count { estimate(it) > 0 },
        profileModelUsage = filtered.groupBy { Triple(it.profileUid, it.providerSnapshot, it.modelSnapshot) }.map { (key, items) ->
            ProfileModelUsage(
                key.first,
                profileNames[key.first] ?: "Deleted profile (${key.first.take(8)})",
                key.second.ifBlank { "Unknown provider" },
                key.third.ifBlank { "Unknown model" },
                items.size,
                items.sumOf { it.outputTokens?.coerceAtLeast(0)?.toLong() ?: 0 },
                items.sumOf(::estimate)
            )
        }.sortedWith(compareByDescending<ProfileModelUsage> { it.runs }.thenBy { it.profileName }.thenBy { it.provider }.thenBy { it.model }.thenBy { it.profileUid }),
        toolCalls = selectedTools.size,
        failedTools = toolUsage.sumOf { it.failures },
        toolUsage = toolUsage,
        days = days
    )
}

@HiltViewModel
class UsageStatisticsViewModel @Inject constructor(
    runDao: AgentRunDao,
    persistenceDao: AgentPersistenceDao,
    settings: SettingRepository,
    database: dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
) : ViewModel() {
    private val days = MutableStateFlow(30)
    fun selectRange(value: Int) {
        require(value in setOf(0, 7, 30))
        days.value = value
    }

    private val scope = MutableStateFlow(UsageScope())
    fun selectProfile(uid: String?) {
        scope.value = scope.value.copy(profileUid = uid)
    }
    fun selectProvider(provider: String?) {
        scope.value = scope.value.copy(provider = provider)
    }

    private val source = combine(
        runDao.observeRecent(10_000),
        persistenceDao.observeRecentToolEvents(10_000),
        settings.observePlatformV2s(),
        days,
        runDao.observeUnreportedOutputLengths()
    ) { runs, tools, profiles, range, lengths ->
        UsageInputs(runs, tools, profiles.associate { it.uid to it.name }, range, lengths)
    }
    val statistics = combine(source, database.invocationDao().statistics(), scope) { source, invocations, selectedScope ->
        scopedUsageStatistics(source, invocations, selectedScope)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UsageStatistics())
}

internal data class UsageScope(val profileUid: String? = null, val provider: String? = null) {
    fun includes(profile: String?, providerName: String): Boolean =
        (profileUid == null || profileUid == profile) && (provider == null || provider == providerName)
}

internal data class UsageInputs(
    val runs: List<AgentRun>,
    val tools: List<ToolEvent>,
    val profileNames: Map<String, String>,
    val range: Int,
    val lengths: List<RunOutputLength>
)

internal fun scopedUsageStatistics(
    source: UsageInputs,
    invocations: List<dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation>,
    scope: UsageScope,
    today: LocalDate = LocalDate.now()
): UsageStatistics {
    val runs = source.runs.filter { scope.includes(it.profileUid, it.providerSnapshot) }
    val runIds = runs.mapTo(mutableSetOf()) { it.runId }
    val tools = if (scope == UsageScope()) source.tools else source.tools.filter { it.runId in runIds }
    val stats = calculateUsageStatistics(runs, source.profileNames, source.range, today, source.lengths, tools)
    val zone = ZoneId.systemDefault()
    val selected = invocations.filter {
        val date = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
        scope.includes(it.profileUid, it.provider) &&
            !date.isAfter(today) &&
            (source.range == 0 || !date.isBefore(today.minusDays(source.range - 1L)))
    }
    val profiles = (source.runs.map { it.profileUid } + invocations.mapNotNull { it.profileUid }).distinct()
        .associateWith { source.profileNames[it] ?: "Deleted profile (${it.take(8)})" }
    val finished = selected.filter { it.status != "RUNNING" }
    val priced = finished.filter { it.costMicros != null && !it.currency.isNullOrBlank() }
    return stats.copy(
        performance = modelPerformance(selected), profilePerformance = profilePerformance(selected, source.profileNames),
        profileNames = source.profileNames, selectedProfile = scope.profileUid, selectedProvider = scope.provider,
        availableProfiles = profiles, availableProviders = (source.runs.map { it.providerSnapshot } + invocations.map { it.provider }).distinct().sorted(),
        recordedCosts = priced.groupBy { it.currency!! }.mapValues { (_, rows) -> rows.sumOf { it.costMicros!! } },
        unpricedRequests = finished.size - priced.size
    )
}
