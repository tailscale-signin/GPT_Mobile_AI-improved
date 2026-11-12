package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter
import dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight
import dev.chungjungsoo.gptmobile.data.network.ProviderCooldown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Private scheduling inputs; credentials and saved checkpoints never enter the UI projection. */
internal class ProviderWaitSchedule private constructor(
    private val original: ProviderWaitDeadline,
    private val scope: String,
    private val limitedProfile: PlatformV2?
) {
    fun project(
        paidDeadline: (String) -> Long? = ProviderCooldown::blockedUntil,
        freeDeadline: (PlatformV2) -> Long? = { FreeProviderPreflight.check(it)?.retryAt }
    ): ProviderWaitDeadline = original.withSchedule(
        effectiveProviderRetryAt(original.retryAt, paidDeadline(scope), limitedProfile?.let(freeDeadline))
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun prepare(row: ProviderWait, profiles: List<PlatformV2>): ProviderWaitSchedule {
            val limited = if (row.scope.startsWith("free-quota:")) {
                runCatching {
                    val saved = json.decodeFromString<ProviderWaitCheckpoint>(row.checkpoint)
                    val decision = requireNotNull(saved.decision)
                    val admitted = requireNotNull(saved.limitPlatform)
                    val authority = requireNotNull(decision.authority)
                    require(saved.version == 2 && decision.mayWait && decision.scope == row.scope && decision.retryAt == row.retryAt)
                    require(decision.profileUid == admitted.uid && decision.model == admitted.model && authority.matches(admitted))
                    val current = requireNotNull(profiles.singleOrNull { it.uid == admitted.uid })
                    require(current.enabled && ConfiguredDispatchAuthority.profileRevision(current) == saved.limitRegisteredRevision)
                    admitted.copy(token = current.token)
                }.getOrNull()
            } else {
                null
            }
            return ProviderWaitSchedule(ProviderWaitDeadline(row.runId, row.fence, row.retryAt), row.scope, limited)
        }

        suspend fun prepare(rows: List<ProviderWait>, loadProfiles: suspend () -> List<PlatformV2>): List<ProviderWaitSchedule> {
            val profiles = if (rows.any { it.scope.startsWith("free-quota:") }) {
                try {
                    loadProfiles()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            return rows.map { prepare(it, profiles) }
        }
    }
}

/** Message progress invalidates only small owner metadata, never the saved checkpoint payload. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<List<ProviderWaitDeadline>>.withCurrentProviderWaitRows(
    observeRows: (List<String>) -> Flow<List<ProviderWait>>
): Flow<List<ProviderWait>> = map { it.sortedBy(ProviderWaitDeadline::runId) }.distinctUntilChanged().flatMapLatest { owners ->
    flow {
        // Revoke the former owners before waiting for the provider_waits query.
        emit(emptyList())
        if (owners.isNotEmpty()) {
            val expected = owners.associateBy(ProviderWaitDeadline::runId)
            emitAll(
                observeRows(expected.keys.toList()).map { rows ->
                    rows.filter { row -> expected[row.runId]?.let { it.fence == row.fence && it.retryAt == row.retryAt } == true }
                }.distinctUntilChanged()
            )
        }
    }
}.distinctUntilChanged()

/** Resolve a saved authority once per row change; obsolete owners cancel before publication. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<List<ProviderWait>>.withCurrentProviderWaitSchedules(
    loadProfiles: suspend () -> List<PlatformV2>,
    paidRevisions: Flow<Long> = ProviderCooldown.revision,
    freeRevisions: Flow<Long> = FreeAiRequestLimiter.shared.revision,
    paidDeadline: (String) -> Long? = ProviderCooldown::blockedUntil,
    freeDeadline: (PlatformV2) -> Long? = { FreeProviderPreflight.check(it)?.retryAt }
): Flow<List<ProviderWaitDeadline>> = distinctUntilChanged().flatMapLatest { rows ->
    flow {
        // Replacing a row also revokes its old revision observer while the new
        // profile lookup is suspended, even when runId and fence are unchanged.
        emit(emptyList())
        val saved = ProviderWaitSchedule.prepare(rows, loadProfiles)
        emitAll(
            combine(paidRevisions, freeRevisions) { _, _ ->
                saved.map { it.project(paidDeadline, freeDeadline) }
            }
        )
    }
}.distinctUntilChanged()
