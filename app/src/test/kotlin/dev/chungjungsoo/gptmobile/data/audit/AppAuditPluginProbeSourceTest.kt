package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditPluginProbeSourceTest {
    @Test
    fun configurationAndDiscoveryNeverClaimToolExecution() = runBlocking {
        var reads = 0
        val source = AppAuditPluginProbeSource({
            listOf(
                target().copy(liveRead = { dispatch ->
                    dispatch()
                    reads++
                    live()
                }),
                target("disabled").copy(enabled = false),
                target("missing").copy(configured = false)
            )
        })
        val plan = source.plan(AppAuditOptions(allowNetworkReads = true))
        assertEquals(9, plan.size)
        assertEquals(0, reads)
        assertEquals(3, plan.count { it.probeId == AppAuditPluginProbeSource.CONFIGURATION })
        assertEquals(3, plan.count { it.probeId == AppAuditPluginProbeSource.TOOL_EXECUTION && it.prerequisite == AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED })
        assertEquals(AppAuditPrerequisite.PERMISSION_REQUIRED, plan.single { it.targetId == "disabled" && it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }.prerequisite)
        assertEquals(AppAuditPrerequisite.NOT_CONFIGURED, plan.single { it.targetId == "missing" && it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }.prerequisite)
        val configuration = source.execute(plan.single { it.targetId == "configured" && it.probeId == AppAuditPluginProbeSource.CONFIGURATION }, context())
        assertEquals(AppAuditEvidenceKind.CONFIGURATION, configuration.evidenceKind)
        assertEquals(AppAuditObservationSource.CONFIGURATION, configuration.observationSource)
        assertEquals(0, reads)
    }

    @Test
    fun networkOptOutNeitherDispatchesNorReads() = runBlocking {
        var reads = 0
        var markers = 0
        val source = AppAuditPluginProbeSource({
            listOf(
                target().copy(liveRead = { dispatch ->
                    dispatch()
                    reads++
                    live()
                })
            )
        })
        val entry = source.plan(AppAuditOptions()).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }
        val result = source.execute(entry, context { markers++ })
        assertEquals(AppAuditOutcome.SKIPPED, result.outcome)
        assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, result.dispatchEvidence)
        assertEquals(0, markers)
        assertEquals(0, reads)
    }

    @Test
    fun liveReadFollowsDurableMarkerAndKeepsMetadataEvidence() = runBlocking {
        val calls = mutableListOf<String>()
        val source = AppAuditPluginProbeSource({
            listOf(
                target().copy(liveRead = { dispatch ->
                    dispatch()
                    calls += "read"
                    live()
                })
            )
        })
        val result = source.execute(discovery(source), context { calls += "marker" })
        assertEquals(listOf("marker", "read"), calls)
        assertEquals(AppAuditOutcome.PASS, result.outcome)
        assertEquals(AppAuditEvidenceKind.LIVE_METADATA, result.evidenceKind)
        assertEquals(AppAuditDispatchEvidence.CONFIRMED, result.dispatchEvidence)
        assertEquals(AppAuditMetric(AppAuditMetricName.TOOL_COUNT, 2.0), result.metrics.single())
    }

    @Test
    fun changedConfigurationRejectsBeforeMarkerWithoutReplacement() = runBlocking {
        var reads = 0
        var markers = 0
        var target = target().copy(liveRead = { dispatch ->
            dispatch()
            reads++
            live()
        })
        val source = AppAuditPluginProbeSource({ listOf(target) })
        val entry = discovery(source)
        target = target.copy(fingerprint = "changed")
        val result = source.execute(entry, context { markers++ })
        assertChanged(result, AppAuditDispatchEvidence.NOT_DISPATCHED)
        assertEquals(0, markers)
        assertEquals(0, reads)
    }

    @Test
    fun markerSuspensionCannotAdmitChangedTarget() = runBlocking {
        var reads = 0
        var target = target().copy(liveRead = { dispatch ->
            dispatch()
            reads++
            live()
        })
        val source = AppAuditPluginProbeSource({ listOf(target) })
        val result = source.execute(discovery(source), context { target = target.copy(fingerprint = "changed") })
        assertChanged(result, AppAuditDispatchEvidence.NOT_DISPATCHED)
        assertEquals(0, reads)
    }

    @Test
    fun postDispatchChangeRetainsObservedReceipt() = runBlocking {
        var target = target()
        target = target.copy(liveRead = { dispatch ->
            dispatch()
            target = target.copy(fingerprint = "changed")
            live()
        })
        val source = AppAuditPluginProbeSource({ listOf(target) })
        val result = source.execute(discovery(source), context())
        assertEquals(live().copy(configChanged = true), result)
    }

    @Test
    fun postDispatchChangeCannotReplaceObservedFailureDetails() = runBlocking {
        var target = target()
        val observed = live().copy(
            outcome = AppAuditOutcome.FAIL,
            reasonCode = "TLS_FAILURE",
            stage = "mcp_discovery",
            dispatchEvidence = AppAuditDispatchEvidence.UNKNOWN,
            retryable = false
        )
        target = target.copy(liveRead = { dispatch ->
            dispatch()
            target = target.copy(fingerprint = "changed")
            observed
        })
        val source = AppAuditPluginProbeSource({ listOf(target) })
        assertEquals(observed.copy(configChanged = true), source.execute(discovery(source), context()))
    }

    @Test
    fun cachedPermanentGateDoesNotConsumeNetworkMarkerOrAttemptRepair() = runBlocking {
        var reads = 0
        var markers = 0
        val gate = live().copy(
            outcome = AppAuditOutcome.BLOCKED,
            reasonCode = "TLS_FAILURE",
            observationSource = AppAuditObservationSource.CACHE,
            dispatchEvidence = AppAuditDispatchEvidence.NOT_DISPATCHED,
            retryable = false
        )
        val source = AppAuditPluginProbeSource({
            listOf(
                target().copy(liveReadPrecheck = gate, liveRead = { dispatch ->
                    dispatch()
                    reads++
                    live()
                })
            )
        })
        assertEquals(gate, source.execute(discovery(source), context { markers++ }))
        assertEquals(0, reads)
        assertEquals(0, markers)
    }

    @Test
    fun markerFailurePropagatesAndCannotDispatch() = runBlocking {
        var reads = 0
        val source = AppAuditPluginProbeSource({
            listOf(
                target().copy(liveRead = { dispatch ->
                    dispatch()
                    reads++
                    live()
                })
            )
        })
        val failure = runCatching { source.execute(discovery(source), context { throw IllegalStateException("checkpoint refused") }) }.exceptionOrNull()
        assertEquals("checkpoint refused", failure?.message)
        assertEquals(0, reads)
    }

    @Test
    fun canceledReadPropagatesWithoutRetry() = runBlocking {
        var reads = 0
        val source = AppAuditPluginProbeSource({
            listOf(
                target().copy(liveRead = { dispatch ->
                    dispatch()
                    reads++
                    awaitCancellation()
                })
            )
        })
        val failure = runCatching { withTimeout(25) { source.execute(discovery(source), context()) } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, reads)
    }

    @Test
    fun emptyGatewaySelectionStaysEmptyDuringPlanningAndExactTargetRevalidation() = runBlocking {
        val selections = mutableListOf<List<String>>()
        val source = AppAuditPluginProbeSource({ profiles ->
            selections += profiles
            profiles.map { uid ->
                target("gateway:$uid").copy(kind = AppAuditPluginKind.GATEWAY, componentId = "gateway_connection", liveRead = { dispatch ->
                    dispatch()
                    live()
                })
            }
        })
        assertTrue(source.plan(AppAuditOptions(allowNetworkReads = true)).isEmpty())
        val entry = source.plan(AppAuditOptions(allowNetworkReads = true, selectedProfileIds = listOf("selected", "selected"))).single { it.probeId == AppAuditPluginProbeSource.GATEWAY_CAPABILITIES }
        assertEquals(AppAuditOutcome.PASS, source.execute(entry, context()).outcome)
        assertEquals(emptyList<String>(), selections.first())
        assertTrue(selections.drop(1).all { it == listOf("selected") })
    }

    @Test
    fun targetLimitReportsOmittedCoverageInsteadOfSilentlyPassing() = runBlocking {
        val source = AppAuditPluginProbeSource({ (0..520).map { target("target-$it") } })
        val plan = source.plan(AppAuditOptions())
        val gap = plan.single { it.probeId == AppAuditPluginProbeSource.INVENTORY_LIMIT }
        val result = source.execute(gap, context())
        assertEquals(512, plan.count { it.probeId == AppAuditPluginProbeSource.CONFIGURATION })
        assertEquals(1537, plan.size)
        assertEquals(AppAuditOutcome.UNTESTED, result.outcome)
        assertEquals(AppAuditMetric(AppAuditMetricName.TARGET_COUNT, 9.0), result.metrics.single())
        assertFalse(plan.any { it.reasonCode == "MCP_DISCOVERY_VERIFIED" })
    }

    @Test
    fun ordinaryInventoriesBeyondSixtyFourRetainEveryTargetIdentity() = runBlocking {
        val targets = (0 until 100).map { target("target-$it") }
        val source = AppAuditPluginProbeSource({ targets })
        val plan = source.plan(AppAuditOptions(allowNetworkReads = true))
        assertEquals(targets.map { it.key }.toSet(), plan.mapNotNull { it.targetId }.toSet())
        assertEquals(300, plan.size)
        assertFalse(plan.any { it.probeId == AppAuditPluginProbeSource.INVENTORY_LIMIT })
    }

    @Test
    fun targetedLookupsDoNotRepeatWholeInventory() = runBlocking {
        var inventories = 0
        var lookups = 0
        val target = target().copy(liveRead = { dispatch ->
            dispatch()
            live()
        })
        val source = AppAuditPluginProbeSource({
            inventories++
            listOf(target)
        }, { key ->
            lookups++
            target.takeIf { it.key == key }
        })
        assertEquals(AppAuditOutcome.PASS, source.execute(discovery(source), context()).outcome)
        assertEquals(1, inventories)
        assertEquals(3, lookups)
    }

    private fun target(key: String = "configured") = AppAuditPluginTarget(key, AppAuditPluginKind.MCP, "mcp_connection", true, true, "fingerprint")

    private fun live() = AppAuditObservation(
        AppAuditOutcome.PASS,
        "MCP_DISCOVERY_VERIFIED",
        evidenceKind = AppAuditEvidenceKind.LIVE_METADATA,
        observationSource = AppAuditObservationSource.LIVE,
        dispatchEvidence = AppAuditDispatchEvidence.CONFIRMED,
        metrics = listOf(AppAuditMetric(AppAuditMetricName.TOOL_COUNT, 2.0))
    )

    private fun context(marker: suspend () -> Unit = {}) = AppAuditProbeContext(AppAuditLimits(), marker)

    private suspend fun discovery(source: AppAuditPluginProbeSource) = source.plan(AppAuditOptions(allowNetworkReads = true)).single { it.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY }

    private fun assertChanged(result: AppAuditObservation, dispatch: AppAuditDispatchEvidence) {
        assertEquals(AppAuditOutcome.BLOCKED, result.outcome)
        assertEquals("PLUGIN_CONFIGURATION_CHANGED", result.reasonCode)
        assertTrue(result.configChanged)
        assertEquals(dispatch, result.dispatchEvidence)
    }
}
