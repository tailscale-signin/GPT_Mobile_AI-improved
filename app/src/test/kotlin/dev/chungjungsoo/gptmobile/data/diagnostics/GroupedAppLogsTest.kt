package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GroupedAppLogsTest {
    @Test fun liveAppendReusesUnchangedRowsAndRetainsEveryNewRecord() {
        val grouping = IncrementalAppLogGroups()
        val first = AppLogEntry(1000, "I", "Model", "Started")
        val second = AppLogEntry(2000, "E", "Worker", "Failed independently")
        val initial = grouping.update(listOf(first, second))
        val third = AppLogEntry(2100, "I", "Model", "Progress")
        val updated = grouping.update(listOf(first, second, third))
        assertSame(initial[0], updated[0])
        assertSame(initial[1], updated[1])
        assertEquals(listOf(first, second, third), updated.flatMap { it.rawDetails })
    }

    @Test fun slidingRingEvictsExpiredEvidenceIncludingMembersOfRepeatedRows() {
        val grouping = IncrementalAppLogGroups()
        val entries = (0..4).map { AppLogEntry(1000L + it, "I", "Model", "Waiting") }
        grouping.update(entries.take(4))
        val retained = entries.drop(2)
        val updated = grouping.update(retained)
        assertEquals(retained, updated.single().rawDetails)
        assertEquals(3, updated.single().repetitions)
        assertEquals(entries[2], updated.single().entry)
        assertEquals(entries.last().time, updated.single().lastTime)
    }

    @Test fun clearAndFrozenSnapshotReplacementNeverImportAbsentLiveRows() {
        val grouping = IncrementalAppLogGroups()
        val entries = (0..3).map { AppLogEntry(1000L + it, "E", "Worker", "Failure $it") }
        grouping.update(entries)
        assertEquals(entries.take(2), grouping.update(entries.take(2)).flatMap { it.rawDetails })
        assertEquals(emptyList<GroupedAppLog>(), grouping.update(emptyList()))
        assertEquals(listOf(entries.last()), grouping.update(listOf(entries.last())).flatMap { it.rawDetails })
    }

    @Test fun recoveredMetadataReadKeepsRawFailuresWithoutResolvingAnotherOperation() {
        val grouping = IncrementalAppLogGroups()
        val failed = AppLogEntry(1000, "D", "Network", "HTTP call failed: unexpected end of stream · incident_id=read-a")
        val exchange = AppLogEntry(1100, "I", "Gateway", "CLIENT_EXCHANGE · incident_id=read-a · transport=TIMEOUT")
        val other = AppLogEntry(1200, "I", "Gateway", "CLIENT_EXCHANGE · incident_id=read-b · transport=TIMEOUT")
        val initial = grouping.update(listOf(failed, exchange, other))
        assertEquals(setOf("read-a", "read-b"), initial.flatMap { it.unresolvedIncidentIds }.toSet())
        val recovered = AppLogEntry(1300, "I", "Gateway", "METADATA_READ_OUTCOME · incident_id=read-a · outcome=COMPLETED")
        val updated = grouping.update(listOf(failed, exchange, other, recovered))
        assertEquals(setOf("read-a", "read-b"), updated.flatMap { it.incidentIds }.toSet())
        assertEquals(setOf("read-b"), updated.flatMap { it.unresolvedIncidentIds }.toSet())
        assertEquals(listOf(failed, exchange, recovered), updated.single { "read-a" in it.incidentIds }.rawDetails)
    }

    @Test fun largeRepeatedGroupRetainsEveryOriginalEntry() {
        val entries = (0 until 2000).map { index -> AppLogEntry(1000L + index, "I", "Model", "Waiting") }
        val group = groupAppLogs(entries).single()
        assertEquals(entries.size, group.repetitions)
        assertEquals(entries, group.rawDetails)
        assertEquals(entries.last().time, group.lastTime)
    }

    @Test fun `consecutive repeats compress without losing count or last timestamp`() {
        val events = listOf(AppLogEntry(1000, "I", "Model", "Waiting"), AppLogEntry(1500, "I", "Model", "Waiting"), AppLogEntry(2000, "I", "Model", "Waiting"))
        val group = groupAppLogs(events).single()
        assertEquals(3, group.repetitions)
        assertEquals(1000L, group.entry.time)
        assertEquals(2000L, group.lastTime)
        assertEquals(3, events.size)
    }

    @Test fun `different severity intervening messages and distant repeats remain distinct`() {
        val first = AppLogEntry(1000, "I", "Model", "Waiting")
        val events = listOf(first, first.copy(time = 1500, level = "E"), first.copy(time = 2000), first.copy(time = 6000))
        assertEquals(4, groupAppLogs(events).size)
    }

    @Test fun `budget warnings across different engines group with raw details`() {
        val events = listOf(
            AppLogEntry(1000, "W", "ToolBudget", "Tool-result byte budget blocked execution · tool=one"),
            AppLogEntry(1500, "I", "Model", "Progress"),
            AppLogEntry(2000, "W", "ToolBudget", "Tool-result byte budget blocked execution · tool=two")
        )
        val groups = groupAppLogs(events)
        assertEquals(2, groups.size)
        assertEquals(2, groups.last().repetitions)
        assertEquals(listOf(events.first(), events.last()), groups.last().rawDetails)
    }

    @Test fun `background telemetry failures group without dropping their original details`() {
        val entries = listOf(
            AppLogEntry(1000, "E", "Android", "E/TransportRuntime.CctTransportBackend: Couldn't open connection"),
            AppLogEntry(1001, "E", "Android", "E/TransportRuntime.CctTransportBackend: java.net.ConnectException"),
            AppLogEntry(1002, "I", "Model", "Completed"),
            AppLogEntry(2000, "E", "Android", "E/TransportRuntime.CctTransportBackend: at upload()")
        )
        val groups = groupAppLogs(entries)
        assertEquals(2, groups.size)
        assertEquals(3, groups.last().repetitions)
        assertEquals(3, groups.last().rawDetails.size)
    }

    @Test fun operationFailureAndItsConsequenceCountOnceEvenAcrossTagsAndLogLevels() {
        val groups = groupAppLogs(
            listOf(
                AppLogEntry(1000, "I", "Runtime", "LOAD_START · nativeOperationId=op-1"),
                AppLogEntry(1100, "I", "Runtime", "LOAD_FAILED · nativeOperationId=op-1 · stage=load"),
                AppLogEntry(1200, "E", "Benchmark", "Profile failed · nativeOperationId=op-1"),
                AppLogEntry(1300, "I", "Runtime", "RECOVERED · nativeOperationId=op-1")
            )
        )
        val group = groups.single()
        assertEquals(4, group.rawDetails.size)
        assertEquals(1, group.incidentIds.size)
        assertEquals("LOAD_FAILED · nativeOperationId=op-1 · stage=load", group.entry.message)
    }

    @Test fun identicalUncorrelatedFailuresNeverCollapseIntoOneCausalIssue() {
        val groups = groupAppLogs(listOf(AppLogEntry(1000, "E", "Runtime", "Load failed"), AppLogEntry(1100, "E", "Runtime", "Load failed")))
        assertEquals(2, groups.size)
        assertEquals(2, groups.flatMap { it.incidentIds }.distinct().size)
    }
}
