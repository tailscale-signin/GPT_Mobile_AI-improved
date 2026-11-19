package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerDatasetTest {
    private val table = AnswerDataset(1, "table", "Checks", listOf("Suite", "Passed"), listOf(listOf("CI", "23"), listOf("Lint", "2")))

    @Test fun acceptsCompleteVersionedRowsAndSortsNumbers() {
        val raw = Json.encodeToString(table)
        assertNull(AnswerDataset.parse(raw.dropLast(1)))
        assertEquals(table, AnswerDataset.parse(raw))
        assertEquals(listOf("2", "23"), table.ordered(1, false).map { it.last() })
        assertEquals(table.rows, table.ordered(1, true))
    }

    @Test fun missingNumbersStayLastAndChartRequiresCompleteNonnegativePairs() {
        val partial = table.copy(rows = table.rows + listOf(listOf("Pending", "")))
        assertEquals(listOf("2", "23", ""), partial.ordered(1, false).map { it.last() })
        assertEquals(listOf("23", "2", ""), partial.ordered(1, true).map { it.last() })
        assertTrue(table.supportsBarChart)
        org.junit.Assert.assertFalse(partial.supportsBarChart)
        org.junit.Assert.assertFalse(table.copy(rows = listOf(listOf("Loss", "-1"))).supportsBarChart)
    }

    @Test fun rejectsInvalidShapesLocationsAndLinks() {
        listOf(
            table.copy(version = 2),
            table.copy(rows = listOf(listOf("partial"))),
            table.copy(rows = List(65) { table.rows.first() }),
            table.copy(type = "bars", rows = listOf(listOf("Cost", "NaN"))),
            table.copy(type = "places", columns = listOf("Place", "Lat", "Lon"), rows = listOf(listOf("A", "91", "0"))),
            table.copy(type = "sources", rows = listOf(listOf("Source", "https://someone@example.com")))
        ).forEach {
            assertNull(AnswerDataset.parse(Json.encodeToString(it)))
        }
        assertNotNull(AnswerDataset.parse(Json.encodeToString(table.copy(type = "bars"))))
    }

    @Test fun metricCardsRequireDistinctLabelsAndFiniteBoundedValues() {
        val metrics = table.copy(type = "metrics", rows = listOf(listOf("Passed", "0"), listOf("Change", "-0.25")))
        assertEquals(metrics, AnswerDataset.parse(Json.encodeToString(metrics)))
        listOf(metrics.rows + listOf(metrics.rows.first()), List(7) { listOf(it.toString(), "0") }, listOf(listOf(" ", "1")), listOf(listOf("Failed", "NaN"))).forEach {
            assertNull(AnswerDataset.parse(Json.encodeToString(metrics.copy(rows = it))))
        }
    }

    @Test fun boundsInteractiveDiagramsAndKeepsCycles() {
        val edges = table.copy(type = "diagram", rows = listOf(listOf("Draft", "Review"), listOf("Review", "Draft")))
        assertEquals(edges, AnswerDataset.parse(Json.encodeToString(edges)))
        listOf(edges.rows + listOf(edges.rows.first()), List(17) { listOf("Draft", it.toString()) }, listOf(listOf("", "Draft"))).forEach {
            assertNull(AnswerDataset.parse(Json.encodeToString(edges.copy(rows = it))))
        }
    }

    @Test fun exportQuotesTextAndNeutralizesSpreadsheetFormulas() {
        val csv = table.csv(listOf(listOf(" =SUM(1)", "-2"), listOf("A,\"B\"", "3")))
        assertTrue(csv.contains("\"' =SUM(1)\",\"-2\"\r\n"))
        assertTrue(csv.contains("\"A,\"\"B\"\"\""))
    }
}
