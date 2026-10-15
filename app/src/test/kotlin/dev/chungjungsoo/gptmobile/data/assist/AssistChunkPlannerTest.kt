package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.context.LocalContextPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistChunkPlannerTest {
    @Test fun plannedChunksFitTheNativePlannerIncludingWorkerInstructions() {
        for (capacity in listOf(2048, 4096, 8192)) {
            for (text in listOf("evidence ", "证据 🧪 ")) {
                val records = (0..11).map { "$it: ${text.repeat(90)}" }
                fun encode(rows: List<String>) = "Select relevant record indices, retaining contrary evidence. Goal: compare bottles.\n" + rows.joinToString("\n")
                val plan = AssistChunkPlanner.plan(records, capacity, 128, ::encode)
                assertTrue(plan.chunks.isNotEmpty())
                assertTrue(plan.unprocessed.isEmpty())
                assertEquals(records, plan.chunks.flatten())
                plan.chunks.forEach { chunk ->
                    val native = LocalContextPlanner.plan(emptyList(), encode(chunk), "w".repeat(500), emptyList(), capacity, 128)
                    assertEquals(128, native.outputReserveTokens)
                }
            }
        }
    }

    @Test fun unknownCapacityAndOversizedRecordsRemainUnprocessed() {
        val records = listOf("a".repeat(10_000))
        for (capacity in listOf(0, 2048)) {
            val plan = AssistChunkPlanner.plan(records, capacity, 128) { it.joinToString() }
            assertTrue(plan.chunks.isEmpty())
            assertEquals(records, plan.unprocessed)
        }
    }
}
