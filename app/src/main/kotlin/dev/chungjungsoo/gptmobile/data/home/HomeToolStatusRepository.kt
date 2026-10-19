package dev.chungjungsoo.gptmobile.data.home

import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.agent.tool.McpConnectionHealth
import dev.chungjungsoo.gptmobile.data.database.dao.ToolConnectionDao
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.HomeToolSubject
import dev.chungjungsoo.gptmobile.data.model.HomeToolSubjectKind
import dev.chungjungsoo.gptmobile.data.model.SettingsHomeTone
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog
import dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolHealthObservation
import dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolStatisticsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

data class HomeToolStatus(val label: String, val tone: SettingsHomeTone, val detail: String = "")
data class HomeToolGroup(val subject: HomeToolSubject, val name: String, val status: HomeToolStatus, val tools: List<String>, val catalogKnown: Boolean, val toolStatuses: Map<String, HomeToolStatus> = emptyMap(), val lastResult: HomeToolStatus? = null)

/** Observes existing transport evidence and catalogs. This repository never connects or probes. */
@Singleton
class HomeToolStatusRepository @Inject constructor(
    private val connections: ToolConnectionDao,
    private val mcp: McpClientManager,
    private val statistics: ToolStatisticsRepository,
    private val native: dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceRegistry
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(features: Flow<AppFeatureSettings>, nowSeconds: Flow<Long>): Flow<List<HomeToolGroup>> =
        connections.observeConnections().flatMapLatest { saved ->
            val remote = saved.map { connection ->
                withOutcomes(
                    ToolPluginId.connection(connection.connectionUid),
                    nowSeconds,
                    connection.connectionUid,
                    connection.alias,
                    connection.updatedAt * 1000,
                    combine(statistics.health(connection.connectionUid), mcp.health, features, nowSeconds) { persisted, live, settings, now ->
                        val catalog = mcp.peekToolsForConnection(connection.connectionUid)?.map { it.name }?.distinct()?.sorted()
                        val enabled = settings.remoteMcpConnections &&
                            settings.isToolPluginEnabled(ToolPluginId.connection(connection.connectionUid)) &&
                            settings.isToolPluginEnabled(ToolServiceCatalog.forConnection(connection).id)
                        HomeToolGroup(
                            HomeToolSubject(connection.connectionUid),
                            connection.name,
                            connectionStatus(enabled, connection.updatedAt * 1000, now * 1000, live[connection.connectionUid], persisted),
                            catalog.orEmpty(),
                            catalog != null
                        )
                    }
                )
            }
            val integrated = ToolServiceCatalog.integrated.map { plugin ->
                withOutcomes(
                    plugin.id,
                    nowSeconds,
                    base = combine(statistics.subjects(plugin.id), features) { subjects, settings ->
                        val builtIn = when (plugin.id) {
                            ToolPluginId.CALCULATOR -> listOf("calculate_expression")
                            ToolPluginId.CURRENT_DATE -> listOf("current_date")
                            ToolPluginId.READ_FILES -> listOf("read_file_slice")
                            ToolPluginId.READ_URL -> listOf("read_url")
                            else -> emptyList()
                        }
                        HomeToolGroup(
                            HomeToolSubject(plugin.id, kind = HomeToolSubjectKind.INTEGRATED_PLUGIN),
                            plugin.name,
                            when {
                                !settings.isToolPluginEnabled(plugin.id) -> HomeToolStatus("Disabled", SettingsHomeTone.NEGATIVE)
                                plugin.id in setOf(ToolPluginId.CALCULATOR, ToolPluginId.CURRENT_DATE) -> HomeToolStatus("Ready locally", SettingsHomeTone.POSITIVE)
                                else -> HomeToolStatus("Not checked", SettingsHomeTone.UNKNOWN)
                            },
                            (builtIn + subjects.map { it.capability }).distinct().sorted(),
                            builtIn.isNotEmpty()
                        )
                    }
                )
            }
            val installedNative = kotlinx.coroutines.flow.flow {
                native.load()
                emitAll(native.state)
            }.flatMapLatest { installations ->
                val groups = installations.mapNotNull { (id, installation) ->
                    val entry = dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.find(id) ?: return@mapNotNull null
                    val service = ToolServiceCatalog.forPackage(entry)
                    withOutcomes(
                        service.id,
                        nowSeconds,
                        instance = id,
                        base = features.map { settings ->
                            val enabled = installation.enabled && settings.isToolPluginEnabled(service.id) && settings.isToolPluginEnabled(id)
                            val available = dev.chungjungsoo.gptmobile.data.marketplace.NativeMarketplaceCatalog.definitions(entry)
                                .map { it.name }.filterNot { it.substringAfterLast("__") in installation.disabledOperations }
                            HomeToolGroup(
                                HomeToolSubject(id, kind = HomeToolSubjectKind.NATIVE_PACKAGE),
                                entry.preset.name,
                                when {
                                    !enabled -> HomeToolStatus("Disabled", SettingsHomeTone.NEGATIVE)
                                    !installation.ready(entry) -> HomeToolStatus("Setup required", SettingsHomeTone.NEGATIVE)
                                    entry.provider == "universal-marketplace" -> HomeToolStatus("Ready locally", SettingsHomeTone.POSITIVE)
                                    else -> HomeToolStatus("Configured", SettingsHomeTone.UNKNOWN)
                                },
                                available,
                                true
                            )
                        }
                    )
                }
                if (groups.isEmpty()) flowOf(emptyList()) else combine(groups) { it.toList() }
            }
            val all = remote + integrated
            combine(if (all.isEmpty()) flowOf(emptyList()) else combine(all) { it.toList() }, installedNative) { base, packages -> base + packages }
        }

    private fun withOutcomes(
        plugin: String,
        now: Flow<Long>,
        instance: String? = null,
        alias: String? = null,
        revisionMillis: Long = 0,
        base: Flow<HomeToolGroup>
    ): Flow<HomeToolGroup> = combine(base, statistics.subjects(plugin), statistics.activity(plugin, 500), now) { group, subjects, calls, seconds ->
        val identities = subjects.filter { instance == null || it.instance == instance }.associateBy { it.key }
        val matching = calls.filter { call -> identities[call.subject]?.epoch == call.epoch && call.startedAt >= revisionMillis }
        val perTool = matching.groupBy { call -> identities.getValue(call.subject).capability.let { if (alias != null) it.removePrefix(alias + "__") else it } }
            .mapValues { (_, rows) -> requestStatus(rows.maxBy { it.startedAt }, seconds * 1000) }
        group.copy(toolStatuses = perTool, lastResult = matching.maxByOrNull { it.startedAt }?.let { requestStatus(it, seconds * 1000) })
    }

    companion object {
        fun requestStatus(call: dev.chungjungsoo.gptmobile.data.telemetry.tool.ToolInvocation, nowMillis: Long): HomeToolStatus {
            val observed = call.finishedAt ?: call.startedAt
            if (nowMillis - observed !in 0..300_000) return HomeToolStatus("Result outdated", SettingsHomeTone.STALE)
            return when (call.outcome) {
                "SUCCESS" -> HomeToolStatus("Last request succeeded", SettingsHomeTone.POSITIVE)
                "FAILURE" -> HomeToolStatus("Last request failed", SettingsHomeTone.NEGATIVE)
                "PARTIAL" -> HomeToolStatus("Last result partial", SettingsHomeTone.STALE)
                "CANCELED" -> HomeToolStatus("Last request cancelled", SettingsHomeTone.UNKNOWN)
                "IN_PROGRESS" -> HomeToolStatus("Running", SettingsHomeTone.ACTIVE)
                else -> HomeToolStatus("Result unknown", SettingsHomeTone.UNKNOWN)
            }
        }

        fun connectionStatus(enabled: Boolean, revisionMillis: Long, nowMillis: Long, live: McpConnectionHealth?, persisted: ToolHealthObservation?): HomeToolStatus {
            if (!enabled) return HomeToolStatus("Disabled", SettingsHomeTone.NEGATIVE)
            val liveTime = listOfNotNull(live?.lastSuccessAtMs, live?.lastFailureAtMs).maxOrNull()
            val saved = persisted?.takeIf { it.observedAt >= revisionMillis && it.expiresAt >= nowMillis && it.observedAt <= nowMillis }
            val useLive = liveTime != null && liveTime >= revisionMillis && nowMillis - liveTime in 0..300_000 && liveTime >= (saved?.observedAt ?: 0)
            val state = if (useLive) live?.state?.name else saved?.state
            return when (state) {
                "CONNECTED" -> HomeToolStatus("Online", SettingsHomeTone.POSITIVE)
                "UNREACHABLE" -> HomeToolStatus("Unreachable", SettingsHomeTone.NEGATIVE)
                "AUTHENTICATION_REQUIRED" -> HomeToolStatus("Authentication required", SettingsHomeTone.NEGATIVE)
                "RECOVERING" -> HomeToolStatus("Reconnecting", SettingsHomeTone.ACTIVE)
                "DEGRADED" -> HomeToolStatus("Degraded", SettingsHomeTone.STALE)
                else -> if (liveTime != null || persisted != null) HomeToolStatus("Status outdated", SettingsHomeTone.STALE) else HomeToolStatus("Not checked", SettingsHomeTone.UNKNOWN)
            }
        }
    }
}

fun homeToolSubjectStatus(subject: HomeToolSubject, group: HomeToolGroup?): HomeToolStatus = when {
    group == null -> HomeToolStatus("Unavailable", SettingsHomeTone.UNKNOWN)
    subject.toolName != null && group.catalogKnown && subject.toolName !in group.tools -> HomeToolStatus("Tool unavailable", SettingsHomeTone.NEGATIVE)
    group.status.tone == SettingsHomeTone.NEGATIVE -> group.status
    subject.toolName != null -> group.toolStatuses[subject.toolName]?.copy(detail = "${group.name} · ${group.status.label}") ?: group.status
    else -> group.status.copy(detail = group.lastResult?.label.orEmpty())
}
