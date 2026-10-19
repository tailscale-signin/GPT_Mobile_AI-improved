package dev.chungjungsoo.gptmobile.data.telemetry.tool

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import java.util.UUID
import kotlinx.serialization.Serializable

data class StatisticsRead<T>(val value: T? = null, val unavailable: Boolean = false)

@Serializable
enum class ToolOutcome { IN_PROGRESS, SUCCESS, FAILURE, PARTIAL, CANCELED, UNKNOWN }

@Serializable
enum class ToolDelivery { PENDING, DELIVERED, TRUNCATED, FAILED }

@Serializable
data class ToolIdentity(val plugin: String, val instance: String, val capability: String) {
    val key: String get() = UUID.nameUUIDFromBytes("$plugin\u0000$instance\u0000$capability".toByteArray()).toString()
    companion object {
        fun of(name: String, connection: String? = null): ToolIdentity {
            val marketplace = dev.chungjungsoo.gptmobile.data.catalog.GitHubMarketplaceCatalog.packages.firstOrNull { name.startsWith(it.preset.alias + "__") }
            val plugin = if (name.startsWith("universal_marketplace__") || connection == "optional-universal-marketplace") {
                "service:universal-marketplace"
            } else if (marketplace != null && connection == null) {
                dev.chungjungsoo.gptmobile.data.model.ToolServiceCatalog.forPackage(marketplace).id
            } else if (connection != null) {
                ToolPluginId.connection(connection)
            } else {
                when {
                    name.startsWith("deep_research_") -> ToolPluginId.DEEP_RESEARCH
                    name.startsWith("health_") -> ToolPluginId.HEALTH_CONNECT
                    name == "memory" || name.startsWith("memory_") || name.startsWith("recall_") -> ToolPluginId.LOCAL_MEMORY
                    name.startsWith("amazon_free") || name.endsWith("__free_native") -> ToolPluginId.AMAZON_FREE
                    name.startsWith("amazon_") -> ToolPluginId.AMAZON_SEARCH
                    name.startsWith("github") -> ToolPluginId.GITHUB
                    name.startsWith("airbnb") -> ToolPluginId.AIRBNB
                    name.startsWith("news") || name.startsWith("google_news") || name.startsWith("hacker_news") -> ToolPluginId.NEWS
                    name.contains("search") -> ToolPluginId.WEB_SEARCH
                    name == "calculate_expression" -> ToolPluginId.CALCULATOR
                    name == "current_date" -> ToolPluginId.CURRENT_DATE
                    name == "device_location" -> ToolPluginId.DEVICE_LOCATION
                    name == "read_file_slice" -> ToolPluginId.READ_FILES
                    name == "read_url" -> ToolPluginId.READ_URL
                    name == "delegate_to_model" -> ToolPluginId.MODEL_DELEGATION
                    else -> "native:$name"
                }
            }
            return ToolIdentity(plugin, connection ?: marketplace?.id ?: "native", name)
        }
    }
}

@Serializable
@Entity(tableName = "tool_subject")
data class ToolSubject(@PrimaryKey val key: String, val plugin: String, val instance: String, val capability: String, val epoch: String, val since: Long)

@Serializable
@Entity(tableName = "tool_invocation", indices = [Index(value = ["subject", "startedAt"]), Index(value = ["root"])])
data class ToolInvocation(
    @PrimaryKey val id: String,
    val subject: String,
    val epoch: String,
    val profile: String,
    val root: String,
    val parent: String?,
    val owner: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val outcome: String = ToolOutcome.IN_PROGRESS.name,
    val delivery: String = ToolDelivery.PENDING.name,
    val reason: String? = null,
    val durationMs: Long? = null,
    val shared: Boolean = false,
    val detailRetained: Boolean = true,
    val authoritativeOutcome: String? = null,
    val producerInvocation: String? = null
)

@Serializable
@Entity(tableName = "tool_rollup", primaryKeys = ["subject", "bucket", "profile"])
data class ToolRollup(
    val subject: String,
    /** -1 is retained lifetime. Other buckets are UTC start hours. Never sum the two. */
    val bucket: Long = -1,
    val total: Long = 0,
    val success: Long = 0,
    val failure: Long = 0,
    val partial: Long = 0,
    val canceled: Long = 0,
    val unknown: Long = 0,
    val inProgress: Long = 0,
    val shared: Long = 0,
    val lastUsed: Long = 0,
    val liveDurationMs: Long = 0,
    val liveSamples: Long = 0,
    /** Bounded successful, non-shared latency samples. Serialized without a Room converter. */
    val latencyBuckets: String = "0,0,0,0,0,0,0,0",
    /** Empty only for pre-v2 history whose profile dimension was not retained. */
    val profile: String = ""
) {
    @get:androidx.room.Ignore
    val determinate: Long get() = success + failure + partial

    @get:androidx.room.Ignore
    val successRate: Double? get() = if (determinate == 0L) null else success * 100.0 / determinate
    fun valid(): Boolean = runCatching {
        val counters = listOf(success, failure, partial, canceled, unknown, inProgress)
        total >= 0 &&
            counters.all { it >= 0 } &&
            total == counters.fold(0L, Math::addExact) &&
            shared in 0..total &&
            liveSamples in 0..success &&
            latencyCounts()?.let { counts -> counts.fold(0L, Math::addExact) <= liveSamples } == true &&
            liveDurationMs >= 0 &&
            lastUsed >= 0 &&
            bucket >= -1
    }.getOrDefault(false)
    fun latencyCounts(): List<Long>? {
        return latencyBuckets.split(',').map { it.toLongOrNull() ?: return null }
            .takeIf { it.size == LATENCY_BOUNDS.size && it.all { count -> count >= 0 } }
    }
    fun withLatency(durationMs: Long): ToolRollup {
        val counts = requireNotNull(latencyCounts()).toMutableList()
        val index = LATENCY_BOUNDS.indexOfFirst { durationMs <= it }.takeIf { it >= 0 } ?: LATENCY_BOUNDS.lastIndex
        counts[index] = Math.addExact(counts[index], 1)
        return copy(liveDurationMs = Math.addExact(liveDurationMs, durationMs), liveSamples = Math.addExact(liveSamples, 1), latencyBuckets = counts.joinToString(","))
    }
    companion object {
        val LATENCY_BOUNDS = listOf(50L, 200L, 1000L, 5000L, 30000L, 120000L, 600000L, 86400000L)
        fun p95UpperBound(rollups: List<ToolRollup>): Long? {
            val counts = LATENCY_BOUNDS.indices.map { index -> rollups.sumOf { it.latencyCounts()?.get(index) ?: 0L } }
            val total = counts.sum()
            if (total == 0L) return null
            val rank = total - total / 20
            var cumulative = 0L
            return LATENCY_BOUNDS[
                counts.indexOfFirst {
                    cumulative += it
                    cumulative >= rank
                }
            ]
        }
    }
    fun transition(previous: ToolOutcome?, next: ToolOutcome): ToolRollup {
        fun change(value: Long, category: ToolOutcome) = Math.addExact(value, (if (next == category) 1L else 0L) - (if (previous == category) 1L else 0L))
        return copy(total = Math.addExact(total, if (previous == null) 1L else 0L), success = change(success, ToolOutcome.SUCCESS), failure = change(failure, ToolOutcome.FAILURE), partial = change(partial, ToolOutcome.PARTIAL), canceled = change(canceled, ToolOutcome.CANCELED), unknown = change(unknown, ToolOutcome.UNKNOWN), inProgress = change(inProgress, ToolOutcome.IN_PROGRESS)).also { check(it.valid()) }
    }
}

@Serializable
@Entity(tableName = "tool_attempt", indices = [Index(value = ["invocation"])])
data class ToolAttempt(@PrimaryKey val id: String, val invocation: String, val outcome: String, val kind: String, val observedAt: Long)

@Serializable
data class ToolStatisticsPreferences(val collect: Boolean = true, val history: Boolean = true, val retentionDays: Int = 30, val includeInBackup: Boolean = false, val includesImportedHistory: Boolean = false)

@Serializable
data class ToolStatisticsSnapshot(val schema: Int = 2, val subjects: List<ToolSubject>, val invocations: List<ToolInvocation>, val rollups: List<ToolRollup>, val attempts: List<ToolAttempt>) {
    fun validate() {
        require(schema in 1..2) { "Unsupported statistics snapshot version" }
        require(subjects.size <= 10000 && invocations.size <= 100000 && rollups.size <= 200000 && attempts.size <= 200000)
        require(subjects.map { it.key }.distinct().size == subjects.size)
        require(invocations.map { it.id }.distinct().size == invocations.size)
        require(rollups.map { Triple(it.subject, it.bucket, it.profile) }.distinct().size == rollups.size)
        fun identifier(value: String): Boolean = value.length in 1..256 && value.all { it.isLetterOrDigit() || it in "-_.:" }
        require(subjects.all { identifier(it.key) && identifier(it.epoch) && identifier(it.plugin) && identifier(it.instance) && identifier(it.capability) && it.since >= 0 })
        require(invocations.all { it.producerInvocation == null || identifier(it.producerInvocation) })
        require(invocations.all { identifier(it.id) && identifier(it.epoch) && identifier(it.root) && identifier(it.profile) && identifier(it.owner) && (it.parent == null || identifier(it.parent)) && it.startedAt >= 0 && (it.finishedAt == null || it.finishedAt >= it.startedAt) && (it.durationMs == null || it.durationMs in 0..86400000) && (it.reason == null || it.reason in ToolStatisticsRepository.REASONS) })
        require(invocations.all { it.authoritativeOutcome == null || it.authoritativeOutcome in setOf("SUCCESS", "FAILURE", "CANCELED", "PARTIAL") })
        val invocationIds = invocations.map { it.id }.toSet()
        require(attempts.map { it.id }.distinct().size == attempts.size)
        require(attempts.all { identifier(it.id) && it.invocation in invocationIds && it.outcome in ToolOutcome.entries.map { outcome -> outcome.name } && it.kind in setOf("UPSTREAM", "MCP_CALL", "HTTP", "LOCAL", "CACHE", "COALESCED") && it.observedAt >= 0 })
        val keys = subjects.map { it.key }.toSet()
        require(rollups.all { it.subject in keys && it.valid() && (it.profile.isEmpty() || identifier(it.profile)) })
        rollups.filter { it.bucket == -1L }.fold(0L) { total, row -> Math.addExact(total, row.total) }
        rollups.filter { it.bucket == -1L }.fold(0L) { total, row -> Math.addExact(total, row.liveDurationMs) }
        val epochs = subjects.associate { it.key to it.epoch }
        require(invocations.all { it.epoch == epochs[it.subject] && it.subject in keys && it.outcome in ToolOutcome.entries.map { o -> o.name } && it.delivery in ToolDelivery.entries.map { d -> d.name } })
    }
}

@Serializable
@Entity(tableName = "tool_health")
data class ToolHealthObservation(@PrimaryKey val connection: String, val state: String, val source: String, val observedAt: Long, val expiresAt: Long)

/** A receipt survives detail pruning and reset; replay cannot create another use. */
@Entity(tableName = "tool_start_receipt")
data class ToolStartReceipt(@PrimaryKey val id: String, val subject: String, val epoch: String)

data class ToolStatisticsScope(val profile: String? = null, val fromHour: Long = -1, val untilHour: Long = Long.MAX_VALUE) {
    init {
        require(fromHour >= -1 && untilHour > fromHour)
    }
    val fromMillis: Long get() = if (fromHour < 0) 0 else Math.multiplyExact(fromHour, 3600000)
    val untilMillis: Long get() = if (untilHour == Long.MAX_VALUE) Long.MAX_VALUE else Math.multiplyExact(untilHour, 3600000)
}
