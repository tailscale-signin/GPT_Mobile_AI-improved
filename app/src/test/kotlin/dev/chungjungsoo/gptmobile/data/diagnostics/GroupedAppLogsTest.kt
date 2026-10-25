package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupedAppLogsTest {
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
