package dev.chungjungsoo.gptmobile.data.telemetry.tool

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class ToolStatisticsRepository internal constructor(
    context: Context,
    private val observationTimeoutMs: Long,
    private val completionTimeoutMs: Long
) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, 150, 250)

    private val database = Room.databaseBuilder(context, ToolStatisticsDatabase::class.java, "tool-statistics-v1.db").addMigrations(ToolStatisticsDatabase.MIGRATION_1_2).build()
    val dao = database.statistics()
    fun profiles() = dao.profiles().catch {
        gap()
        emit(emptyList())
    }
    fun attempts(plugin: String, scope: ToolStatisticsScope, limit: Int = 100) = dao.observedAttempts(plugin, scope.profile, scope.fromMillis, scope.untilMillis, limit).catch {
        gap()
        emit(emptyList())
    }

    fun summaries(plugin: String, scope: ToolStatisticsScope = ToolStatisticsScope()) = dao.summary(plugin, scope.profile, scope.fromHour, scope.untilHour).map { StatisticsRead(value = it) }.catch {
        gap()
        emit(StatisticsRead(unavailable = true))
    }
    fun subjects(plugin: String) = dao.observeSubjects(plugin).catch {
        gap()
        emit(emptyList())
    }
    fun activity(plugin: String, limit: Int, scope: ToolStatisticsScope = ToolStatisticsScope(), outcome: String? = null) = dao.activity(plugin, limit, profile = scope.profile, fromMillis = scope.fromMillis, untilMillis = scope.untilMillis, outcome = outcome).catch {
        gap()
        emit(emptyList())
    }
    fun chart(plugin: String, since: Long, scope: ToolStatisticsScope = ToolStatisticsScope()) = dao.chart(plugin, since, scope.profile, scope.untilHour).catch {
        gap()
        emit(emptyList())
    }
    fun health(uid: String) = dao.health(uid).catch {
        gap()
        emit(null)
    }
    private val prefs = context.getSharedPreferences("tool-statistics-preferences", Context.MODE_PRIVATE)
    private val mutablePreferences = MutableStateFlow(readPreferences())
    val preferences = mutablePreferences.asStateFlow()
    private val mutableGap = MutableStateFlow(prefs.getBoolean("coverageGap", false))
    val coverageGap = mutableGap.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val healthQueue = kotlinx.coroutines.channels.Channel<ToolHealthObservation>(64)
    private val preferencesMutex = Mutex()
    init {
        scope.launch {
            for (observation in healthQueue) safe { dao.put(observation) }
        }
        scope.launch {
            while (isActive) {
                delay(24L * 60 * 60 * 1000)
                safe { database.withTransaction { pruneDetails() } }
            }
        }
    }
    fun observeHealth(connection: String, state: String, source: String, observedAt: Long) {
        if (!preferences.value.collect) return
        if (!healthQueue.trySend(ToolHealthObservation(connection, state, source, observedAt, if (source == "CONFIG_CHANGED") 0 else observedAt + 300000)).isSuccess) gap()
    }
    private val recovery = scope.async {
        safe {
            database.withTransaction {
                dao.interrupted().forEach { finish(it.id, ToolOutcome.UNKNOWN, ToolDelivery.PENDING, "PROCESS_INTERRUPTED", null, false) }
                pruneDetails()
            }
        }
    }
    private fun readPreferences() = ToolStatisticsPreferences(prefs.getBoolean("collect", true), prefs.getBoolean("history", true), prefs.getInt("retentionDays", 30), prefs.getBoolean("includeInBackup", false), prefs.getBoolean("includesImportedHistory", false))
    suspend fun preferences(transform: (ToolStatisticsPreferences) -> ToolStatisticsPreferences) = withContext(Dispatchers.IO) {
        preferencesMutex.withLock {
            val value = transform(preferences.value)
            require(value.retentionDays in listOf(7, 30, 90))
            check(prefs.edit().putBoolean("collect", value.collect).putBoolean("history", value.history).putInt("retentionDays", value.retentionDays).putBoolean("includeInBackup", value.includeInBackup).commit())
            if (!value.collect) gap()
            mutablePreferences.value = value
            safe { database.withTransaction { pruneDetails() } }
        }
    }
    private suspend fun pruneDetails() {
        val now = System.currentTimeMillis()
        val before = now - readPreferences().retentionDays * 86400000L
        dao.prune(before)
        dao.redactUnresolved(before)
        dao.pruneAttempts()
        dao.pruneHourly(now / 3600000L - 90 * 24)
    }
    private fun gap() {
        mutableGap.value = true
        prefs.edit().putBoolean("coverageGap", true).apply()
    }
    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        gap()
        null
    }

    suspend fun begin(identity: ToolIdentity, profile: String, root: String, owner: String, callId: String = UUID.randomUUID().toString()): String? {
        if (!preferences.value.collect) return null
        return withTimeoutOrNull(observationTimeoutMs) {
            recovery.await()
            safe {
                database.withTransaction {
                    val now = System.currentTimeMillis()
                    val subject = dao.subject(identity.key) ?: ToolSubject(identity.key, identity.plugin, identity.instance, identity.capability.take(128).map { if (it.isLetterOrDigit() || it in "-_.:") it else '_' }.joinToString(""), UUID.randomUUID().toString(), now).also { dao.put(it) }
                    val parent = kotlinx.coroutines.currentCoroutineContext()[ToolStatisticsContext]?.invocationId
                    val id = UUID.nameUUIDFromBytes("${identity.key}\u0000$profile\u0000$root\u0000$callId".toByteArray()).toString()
                    // The logical observer allocates identity before dispatch, not on delivery.
                    if (dao.receipt(id) != null) return@withTransaction null
                    dao.put(ToolStartReceipt(id, identity.key, subject.epoch))
                    val call = ToolInvocation(id, identity.key, subject.epoch, profile, root, parent, owner, now, detailRetained = preferences.value.history)
                    dao.put(call)
                    for (bucket in listOf(-1L, now / 3600000L)) {
                        val rollup = dao.rollup(call.subject, bucket, call.profile) ?: ToolRollup(call.subject, bucket, profile = call.profile)
                        dao.put(rollup.transition(null, ToolOutcome.IN_PROGRESS).copy(lastUsed = now))
                    }
                    call.id
                }
            }
        }.also { if (it == null) gap() }
    }

    suspend fun complete(id: String?, outcome: ToolOutcome, delivery: ToolDelivery, reason: String?, durationMs: Long?, shared: Boolean, producer: String? = null) {
        if (id == null) return
        val completed = withTimeoutOrNull(completionTimeoutMs) {
            safe {
                database.withTransaction { finish(id, outcome, delivery, reason, durationMs, shared, producer) }
                true
            }
        }
        if (completed != true) gap()
    }
    private suspend fun finish(id: String, reportedOutcome: ToolOutcome, delivery: ToolDelivery, reason: String?, durationMs: Long?, shared: Boolean, producer: String? = null) {
        val old = dao.invocation(id) ?: return
        val subject = dao.subject(old.subject) ?: return
        if (subject.epoch != old.epoch) return
        dao.settleAttempts(id)
        val previous = ToolOutcome.valueOf(old.outcome)
        val outcome = if (reportedOutcome == ToolOutcome.UNKNOWN) old.authoritativeOutcome?.let(ToolOutcome::valueOf) ?: reportedOutcome else reportedOutcome
        if (previous != ToolOutcome.IN_PROGRESS && previous != ToolOutcome.UNKNOWN) return
        if (previous == outcome && old.delivery == delivery.name) return
        val validDuration = durationMs?.coerceIn(0L, 86400000L)
        val measured = outcome == ToolOutcome.SUCCESS && !shared && validDuration != null && old.durationMs == null
        for (bucket in listOf(-1L, old.startedAt / 3600000L)) {
            val rollup = dao.rollup(old.subject, bucket, old.profile) ?: dao.rollup(old.subject, bucket) ?: continue
            val next = rollup.transition(previous, outcome)
            val withShared = next.copy(shared = Math.addExact(next.shared, if (shared && !old.shared) 1L else 0L))
            dao.put(if (measured) withShared.withLatency(validDuration!!) else withShared)
        }
        // Only bounded reason codes are permitted; never copy arbitrary exception/result strings.
        val code = reason?.takeIf { it in REASONS }
        dao.put(old.copy(outcome = outcome.name, delivery = delivery.name, reason = code, finishedAt = System.currentTimeMillis(), durationMs = validDuration, shared = shared, producerInvocation = producer ?: old.producerInvocation))
        if (!old.detailRetained && delivery != ToolDelivery.PENDING && outcome !in setOf(ToolOutcome.IN_PROGRESS, ToolOutcome.UNKNOWN)) {
            dao.removeInvocation(id)
            dao.pruneAttempts()
        }
    }

    /** Final server evidence can reconcile an uncertain original use without creating a new use. */
    suspend fun reconcileRemote(id: String, outcome: ToolOutcome) {
        require(outcome in setOf(ToolOutcome.SUCCESS, ToolOutcome.FAILURE, ToolOutcome.CANCELED, ToolOutcome.PARTIAL))
        withTimeoutOrNull(completionTimeoutMs) {
            safe {
                database.withTransaction {
                    val call = dao.invocation(id) ?: return@withTransaction
                    if (dao.subject(call.subject)?.epoch != call.epoch) return@withTransaction
                    if (call.outcome !in setOf(ToolOutcome.IN_PROGRESS.name, ToolOutcome.UNKNOWN.name)) return@withTransaction
                    dao.put(call.copy(authoritativeOutcome = outcome.name))
                    if (call.outcome == ToolOutcome.UNKNOWN.name) finish(id, outcome, ToolDelivery.valueOf(call.delivery), null, null, call.shared)
                }
            }
        }
    }

    /** An executor can reject before dispatch after its final permission check. It is not a use. */
    suspend fun rejectedBeforeDispatch(id: String?) {
        if (id == null) return
        withTimeoutOrNull(completionTimeoutMs) {
            safe {
                database.withTransaction {
                    val call = dao.invocation(id) ?: return@withTransaction
                    if (call.outcome != ToolOutcome.IN_PROGRESS.name) return@withTransaction
                    for (bucket in listOf(-1L, call.startedAt / 3600000L)) {
                        dao.rollup(call.subject, bucket, call.profile)?.let { dao.put(it.copy(total = it.total - 1, inProgress = it.inProgress - 1)) }
                    }
                    dao.removeInvocation(id)
                }
            }
        }
    }
    suspend fun attempt(invocation: String, id: String, outcome: ToolOutcome, kind: String = "MCP_CALL") {
        require(kind in setOf("MCP_CALL", "HTTP", "LOCAL"))
        withTimeoutOrNull(observationTimeoutMs) {
            safe {
                database.withTransaction {
                    val call = dao.invocation(invocation) ?: return@withTransaction
                    if (dao.subject(call.subject)?.epoch != call.epoch) return@withTransaction
                    dao.put(ToolAttempt(id, invocation, outcome.name, kind, System.currentTimeMillis()))
                }
            }
        }
    }
    suspend fun delivered(id: String) {
        withTimeoutOrNull(completionTimeoutMs) {
            safe {
                database.withTransaction {
                    val call = dao.invocation(id) ?: return@withTransaction
                    if (dao.subject(call.subject)?.epoch == call.epoch && call.delivery == ToolDelivery.PENDING.name) {
                        dao.put(call.copy(delivery = ToolDelivery.DELIVERED.name))
                        if (!call.detailRetained && call.outcome !in setOf("IN_PROGRESS", "UNKNOWN")) dao.removeInvocation(id)
                    }
                }
            }
        }
    }

    suspend fun clear(pluginOrSubject: String) = database.withTransaction {
        dao.subjects().filter { it.plugin == pluginOrSubject || it.key == pluginOrSubject || pluginOrSubject == "*" }.forEach {
            dao.clearAttempts(it.key)
            dao.clearInvocations(it.key)
            dao.clearRollups(it.key)
            dao.put(it.copy(epoch = UUID.randomUUID().toString(), since = System.currentTimeMillis()))
        }
    }
    fun validateSnapshot(raw: String): ToolStatisticsSnapshot {
        require(raw.length <= 25 * 1024 * 1024)
        return Json.decodeFromString<ToolStatisticsSnapshot>(raw).also { it.validate() }
    }
    suspend fun export(): String = database.withTransaction {
        val calls = dao.invocations().filter { it.detailRetained || it.outcome in setOf("IN_PROGRESS", "UNKNOWN") }
        val ids = calls.map { it.id }.toSet()
        Json.encodeToString(ToolStatisticsSnapshot(subjects = dao.subjects(), invocations = calls, rollups = dao.rollups(), attempts = dao.attempts().filter { it.invocation in ids }))
    }

    suspend fun restore(raw: String) {
        require(raw.length <= 25 * 1024 * 1024)
        val snapshot = validateSnapshot(raw)
        database.withTransaction {
            dao.clearAttempts()
            dao.clearInvocations()
            dao.clearRollups()
            dao.clearSubjects()
            // Fresh local identities prevent pre-restore callbacks from editing imported history.
            val epochs = snapshot.subjects.associate { it.key to UUID.randomUUID().toString() }
            val ids = snapshot.invocations.associate { it.id to UUID.randomUUID().toString() }
            snapshot.subjects.forEach { dao.put(it.copy(epoch = epochs.getValue(it.key))) }
            snapshot.rollups.forEach { dao.put(it) }
            snapshot.invocations.forEach { dao.put(it.copy(id = ids.getValue(it.id), epoch = epochs.getValue(it.subject), parent = it.parent?.let(ids::get), producerInvocation = it.producerInvocation?.let(ids::get))) }
            snapshot.attempts.forEach { dao.put(it.copy(id = UUID.randomUUID().toString(), invocation = ids.getValue(it.invocation))) }
            dao.interrupted().forEach { finish(it.id, ToolOutcome.UNKNOWN, ToolDelivery.PENDING, "RESTORED_HISTORY", null, false) }
        }
        prefs.edit().putBoolean("includesImportedHistory", true).apply()
        mutablePreferences.value = mutablePreferences.value.copy(includesImportedHistory = true)
        gap()
    }
    companion object {
        internal val REASONS = setOf("PROCESS_INTERRUPTED", "RESTORED_HISTORY", "TOOL_ERROR", "AUTH_REQUIRED", "RATE_LIMITED", "TIMEOUT_OUTCOME_UNKNOWN", "DISPATCH_OUTCOME_UNKNOWN", "CANCELED", "DELIVERY_FAILED")
    }
}
