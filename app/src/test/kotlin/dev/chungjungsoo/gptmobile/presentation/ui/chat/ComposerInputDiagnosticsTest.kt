package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportDiagnosticSnapshotCache
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerInputDiagnosticsTest {
    @Test fun retriesOnceAfterObservedWindowInterruption() {
        val recovery = ComposerFocusRecovery()
        val focused = initial.copy(focused = true, imeVisible = true)
        val interrupted = focused.copy(windowFocused = false, imeVisible = false)
        assertFalse(recovery.observe(focused, false))
        assertFalse(recovery.observe(interrupted, false))
        assertTrue(recovery.observe(interrupted.copy(windowFocused = true), false))
        assertFalse(recovery.observe(focused, false))
        assertFalse(recovery.observe(interrupted, false))
        assertFalse(recovery.observe(interrupted.copy(windowFocused = true), false))
    }

    @Test fun respectsBackExplicitReleaseAndHardwareKeyboard() {
        val focused = initial.copy(focused = true, imeVisible = true)
        val hidden = focused.copy(imeVisible = false)
        val recovery = ComposerFocusRecovery()
        recovery.observe(focused, false)
        assertFalse(recovery.observe(hidden, false))
        assertFalse(recovery.observe(hidden.copy(windowFocused = false), false))
        assertFalse(recovery.observe(hidden, false))
        recovery.observe(focused, false)
        recovery.observe(hidden.copy(windowFocused = false), false)
        recovery.release()
        assertFalse(recovery.observe(hidden, false))
        recovery.observe(initial, true)
        recovery.observe(focused, true)
        recovery.observe(hidden.copy(windowFocused = false), true)
        assertFalse(recovery.observe(hidden, true))
    }

    @Test fun actualCaptureSurvivesOriginRetentionAndTheApprovedPublicPayload() {
        var elapsed = 0L
        var wall = 1_000_000L
        val cache = ReportDiagnosticSnapshotCache(clock = { wall })
        val origin = requireNotNull(cache.captureOrigin())
        val collector = ComposerInputDiagnostics(
            generation = 7,
            originIsCurrent = { cache.isCurrent(origin) },
            publish = { fields, capturedAt ->
                cache.retain(requireNotNull(IssueReportDiagnostics.fromFields("MANUAL_REPORT", fields, capturedAt)), origin)
            },
            elapsedMillis = { elapsed },
            wallMillis = { wall }
        )
        collector.attach(initial)
        elapsed += 8
        wall += 8
        collector.observe(initial.copy(focused = true, imeVisible = true))
        elapsed += 12
        wall += 12
        collector.release(ComposerInputDiagnostics.Release.BACKGROUND_TAP)
        collector.release(ComposerInputDiagnostics.Release.BACKGROUND_TAP)
        val snapshot = requireNotNull(cache.latest("APP"))
        assertEquals(2, snapshot.requiredReporterVersion())
        assertTrue("COMPOSER_IME_EVIDENCE_V1" in snapshot.requiredServiceFeatures())
        assertTrue(snapshot.omittedFields.isEmpty())
        val context = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = wall).withDiagnostics(snapshot)
        val report = IssueReport("11111111-1111-4111-8111-111111111111", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Composer observations", context = context)
        val approved = report.approvedPayload()
        val roundTrip = requireNotNull(IssueReportDiagnostics.fromJson(IssueReport.json.parseToJsonElement(approved).jsonObject["context"]?.jsonObject?.get("diagnostics")))
        assertEquals(snapshot, roundTrip)
        assertEquals(JsonPrimitive(7), roundTrip.values["composer_generation"])
        assertEquals(JsonPrimitive("RELEASE_BACKGROUND_TAP"), roundTrip.values["composer_event_4"])
        assertEquals(JsonPrimitive("RELEASE_BACKGROUND_TAP"), roundTrip.values["composer_event_5"])
        assertEquals(JsonPrimitive(20), roundTrip.values["composer_event_1_age_ms"])
        assertEquals(JsonPrimitive(0), roundTrip.values["composer_event_5_age_ms"])
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), roundTrip).encodeToByteArray().size <= 3072)
        assertTrue(approved.encodeToByteArray().size <= 8192)
        assertFalse(approved.contains("typed_text"))
        assertFalse(approved.contains("key_contents"))
    }

    @Test fun initialObservationEstablishesStateWithoutInventingTransitions() {
        val fixture = Fixture()
        fixture.collector.attach(initial.copy(focused = true, imeVisible = true))
        val captured = fixture.latest()
        assertEquals(listOf("ATTACHED"), events(captured))
        assertEquals(listOf(0L), ages(captured))
        assertEquals(0, captured["composer_focus_transitions"])
        assertEquals(0, captured["composer_ime_transitions"])
        assertEquals(true, captured["composer_focused"])
        assertEquals(true, captured["composer_ime_visible"])
        assertEquals("RESUMED", captured["composer_lifecycle"])
        assertEquals("TOUCH", captured["composer_input_mode"])
    }

    @Test fun orderedFocusImeAndReleaseObservationsKeepRepeatedValuesAndElapsedAges() {
        val fixture = Fixture()
        fixture.collector.attach(initial)
        fixture.advance(10)
        fixture.collector.observe(initial.copy(focused = true))
        fixture.advance(10)
        fixture.collector.observe(initial.copy(focused = true, imeVisible = true))
        fixture.advance(10)
        fixture.collector.release(ComposerInputDiagnostics.Release.SEND)
        fixture.advance(10)
        fixture.collector.observe(initial.copy(imeVisible = true))
        fixture.advance(10)
        fixture.collector.observe(initial)
        assertEquals(listOf("ATTACHED", "EDITOR_FOCUSED", "IME_VISIBLE", "RELEASE_SEND", "EDITOR_UNFOCUSED", "IME_HIDDEN"), events(fixture.latest()))
        assertEquals(listOf(50L, 40L, 30L, 20L, 10L, 0L), ages(fixture.latest()))
        assertEquals(2, fixture.latest()["composer_focus_transitions"])
        assertEquals(2, fixture.latest()["composer_ime_transitions"])
        assertEquals(false, fixture.latest()["composer_focused"])
        assertEquals(false, fixture.latest()["composer_ime_visible"])
    }

    @Test fun theEightSlotRingReportsEachDroppedRecordedEventAndCapsCounters() {
        val fixture = Fixture()
        fixture.collector.attach(initial)
        repeat(300) { index ->
            fixture.advance(1)
            fixture.collector.observe(initial.copy(focused = index % 2 == 0))
        }
        assertEquals(8, events(fixture.latest()).size)
        assertEquals(293, fixture.latest()["composer_events_dropped"])
        assertEquals(255, fixture.latest()["composer_focus_transitions"])
        assertEquals(0, fixture.latest()["composer_ime_transitions"])
        assertEquals((7L downTo 0L).toList(), ages(fixture.latest()))
        assertEquals(4, events(fixture.latest()).count { it == "EDITOR_FOCUSED" })
        assertEquals(4, events(fixture.latest()).count { it == "EDITOR_UNFOCUSED" })
        assertFalse(fixture.latest().containsKey("composer_event_9"))
    }

    @Test fun retentionExpiresOnlyRecordedHistoryAndUnchangedObservationDoesNotRefreshCapture() {
        val fixture = Fixture()
        fixture.collector.attach(initial)
        val first = fixture.captures.single()
        fixture.advance(600_000)
        fixture.collector.observe(initial)
        assertEquals(listOf(first), fixture.captures)
        fixture.collector.release(ComposerInputDiagnostics.Release.BACKGROUND_TAP)
        assertEquals(listOf(600_000L, 0L), ages(fixture.latest()))
        fixture.advance(1)
        fixture.collector.release(ComposerInputDiagnostics.Release.BACKGROUND_TAP)
        assertEquals(listOf("RELEASE_BACKGROUND_TAP", "RELEASE_BACKGROUND_TAP"), events(fixture.latest()))
        assertEquals(listOf(1L, 0L), ages(fixture.latest()))
        assertEquals(1, fixture.latest()["composer_events_dropped"])
        assertEquals(listOf("ATTACHED"), events(first.first))
        assertEquals(1_000_000L, first.second)
    }

    @Test fun eachAppReleaseActorRecordsARequestWithoutInventingImeOrFocusLoss() {
        val fixture = Fixture()
        fixture.collector.attach(initial.copy(focused = true, imeVisible = true))
        ComposerInputDiagnostics.Release.entries.forEach(fixture.collector::release)
        assertEquals(listOf("ATTACHED") + ComposerInputDiagnostics.Release.entries.map { "RELEASE_${it.name}" }, events(fixture.latest()))
        assertEquals(true, fixture.latest()["composer_focused"])
        assertEquals(true, fixture.latest()["composer_ime_visible"])
        assertEquals(0, fixture.latest()["composer_focus_transitions"])
        assertEquals(0, fixture.latest()["composer_ime_transitions"])
    }

    @Test fun lifecycleWindowInputAndEnabledEvidenceUsesObservedState() {
        val fixture = Fixture()
        fixture.collector.attach(initial)
        fixture.collector.lifecycle(ComposerInputDiagnostics.LifecycleEvent.PAUSE)
        fixture.collector.observe(initial.copy(enabled = false, windowFocused = false, inputMode = ComposerInputDiagnostics.InputMode.KEYBOARD))
        fixture.collector.lifecycle(ComposerInputDiagnostics.LifecycleEvent.STOP)
        assertEquals(listOf("ATTACHED", "LIFECYCLE_PAUSE", "CHAT_DISABLED", "WINDOW_UNFOCUSED", "INPUT_KEYBOARD", "LIFECYCLE_STOP"), events(fixture.latest()))
        assertEquals("CREATED", fixture.latest()["composer_lifecycle"])
        assertEquals(false, fixture.latest()["composer_enabled"])
        assertEquals(false, fixture.latest()["composer_window_focused"])
        assertEquals("KEYBOARD", fixture.latest()["composer_input_mode"])
        assertEquals(0, fixture.latest()["composer_focus_transitions"])
        assertEquals(0, fixture.latest()["composer_ime_transitions"])
    }

    @Test fun anOriginThatWasNotAuthorizedCannotBecomeCollectableLater() {
        val fixture = Fixture()
        fixture.allowed = false
        fixture.collector.attach(initial)
        fixture.allowed = true
        fixture.collector.attach(initial.copy(focused = true))
        fixture.collector.release(ComposerInputDiagnostics.Release.SEND)
        assertTrue(fixture.captures.isEmpty())
    }

    @Test fun revokedAndRetiredCollectorsDoNotReactivateOrOverwriteANewLifetime() {
        val fixture = Fixture()
        fixture.collector.attach(initial)
        fixture.allowed = false
        fixture.collector.observe(initial.copy(focused = true))
        fixture.allowed = true
        fixture.collector.observe(initial.copy(focused = true, imeVisible = true))
        fixture.collector.release(ComposerInputDiagnostics.Release.SEND)
        fixture.collector.attach(initial)
        assertEquals(1, fixture.captures.size)
        val fresh = fixture.newCollector(2)
        fresh.attach(initial.copy(focused = true))
        fresh.detach()
        val count = fixture.captures.size
        fixture.collector.detach()
        fresh.release(ComposerInputDiagnostics.Release.DISPOSE)
        fresh.observe(initial)
        assertEquals(count, fixture.captures.size)
        assertEquals(2, fixture.latest()["composer_generation"])
        assertEquals(listOf("ATTACHED", "DETACHED"), events(fixture.latest()))
    }

    @Test fun revocationBetweenObservationAndRetentionCannotRepopulateTheCache() {
        var allowed = true
        val cache = ReportDiagnosticSnapshotCache(canRetain = { allowed }, clock = { 1_000_000L })
        val origin = requireNotNull(cache.captureOrigin())
        val collector = ComposerInputDiagnostics(
            generation = 1,
            originIsCurrent = { cache.isCurrent(origin) },
            publish = { fields, capturedAt ->
                val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("MANUAL_REPORT", fields, capturedAt))
                allowed = false
                cache.clear()
                allowed = true
                cache.retain(snapshot, origin)
            },
            elapsedMillis = { 0L },
            wallMillis = { 1_000_000L }
        )
        collector.attach(initial)
        assertNull(cache.latest("APP"))
        collector.release(ComposerInputDiagnostics.Release.SEND)
        assertNull(cache.latest("APP"))
    }

    @Test fun oneSnapshotObservationKeepsAllCurrentStateCoherentAndFinite() {
        val fixture = Fixture()
        fixture.collector.attach(initial)
        fixture.collector.observe(initial.copy(focused = true, imeVisible = true, enabled = false, windowFocused = false, inputMode = ComposerInputDiagnostics.InputMode.KEYBOARD))
        fixture.captures.drop(1).forEach { (fields, _) ->
            assertEquals(true, fields["composer_focused"])
            assertEquals(true, fields["composer_ime_visible"])
            assertEquals(false, fields["composer_enabled"])
            assertEquals(false, fields["composer_window_focused"])
            assertEquals("KEYBOARD", fields["composer_input_mode"])
        }
        assertTrue(fixture.latest().keys.all { it in setOf("diagnostic_area", "diagnostic_capture_mode") || it.startsWith("composer_") })
        assertEquals(0L, ages(fixture.latest()).last())
    }

    @Test fun coalescedPlatformChangesShareOneClockSampleAndOnePublishedCapture() {
        var elapsedSamples = 0L
        var wallSamples = 0L
        val captured = mutableListOf<Map<String, Any?>>()
        val collector = ComposerInputDiagnostics(
            generation = 1,
            originIsCurrent = { true },
            publish = { fields, _ -> captured += fields },
            elapsedMillis = { ++elapsedSamples },
            wallMillis = { 1_000_000 + ++wallSamples }
        )
        collector.attach(initial)
        collector.observe(initial.copy(focused = true, imeVisible = true, enabled = false, windowFocused = false, inputMode = ComposerInputDiagnostics.InputMode.KEYBOARD))
        assertEquals(2L, elapsedSamples)
        assertEquals(2L, wallSamples)
        assertEquals(2, captured.size)
        assertEquals(listOf("ATTACHED", "EDITOR_FOCUSED", "IME_VISIBLE", "CHAT_DISABLED", "WINDOW_UNFOCUSED", "INPUT_KEYBOARD"), events(captured.last()))
        assertEquals(listOf(1L, 0L, 0L, 0L, 0L, 0L), ages(captured.last()))
    }

    private class Fixture {
        var allowed = true
        var elapsed = 0L
        var wall = 1_000_000L
        val captures = mutableListOf<Pair<Map<String, Any?>, Long>>()
        val collector = newCollector(1)
        fun newCollector(generation: Int) = ComposerInputDiagnostics(generation, { allowed }, { fields, time -> captures += fields to time }, { elapsed }, { wall })
        fun advance(millis: Long) {
            elapsed += millis
            wall += millis
        }
        fun latest(): Map<String, Any?> = captures.last().first
    }

    private fun events(fields: Map<String, Any?>): List<String> = (1..8).mapNotNull { fields["composer_event_$it"] as? String }
    private fun ages(fields: Map<String, Any?>): List<Long> = (1..8).mapNotNull { fields["composer_event_${it}_age_ms"] as? Long }

    private val initial = ComposerInputDiagnostics.State(false, false, true, true, ComposerInputDiagnostics.LifecycleState.RESUMED, ComposerInputDiagnostics.InputMode.TOUCH)
}
