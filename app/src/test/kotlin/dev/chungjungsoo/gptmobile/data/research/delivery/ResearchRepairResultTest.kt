package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchRepairResultTest {
    private val source = ResearchSource("S1", "https://example.org/trial", "Trial", "Read", "The trial enrolled 20 participants. Scores were 9 versus 14 points.")
    private val packet = ResearchDeliveryPacket.build(ResearchSnapshot("trial", sources = listOf(source)), ResearchReportDetail.BRIEF)
    private val good = "The trial enrolled 20 participants. [S1]"
    private val bad = "The trial cured 999 participants. [S1]"
    private val original = "$good\n\n$bad\n\nPartial coverage. Independent review is not available."

    private fun correction(id: String, digest: String, text: String = "Scores were 9 versus 14 points. [S1]"): String = buildJsonObject {
        put(
            "replacements",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("block_id", id)
                        put("previous_sha256", digest)
                        put("text", text)
                    }
                )
            )
        )
        put("append", "## References\n\n[S1] https://example.org/trial")
    }.toString()

    private fun reason(reply: String): ResearchRepairResult.Reason =
        (ResearchReportRepair.applyResult(original, reply, packet) as ResearchRepairResult.Rejected).reason

    @Test fun nonemptyMalformedCorrectionIsNotClassifiedAsAnEmptyResponse() {
        assertEquals(ResearchRepairResult.Reason.INVALID_JSON, reason("{\"replacements\":["))
        assertEquals(ResearchRepairResult.Reason.EMPTY_RESPONSE, reason(" \n "))
        assertEquals(ResearchRepairResult.Reason.INVALID_SCHEMA, reason("[]"))
    }

    @Test fun staleOrUnknownAnchorsRetainDistinctRejections() {
        val block = ResearchReportRepair.blocks(original, packet)[1]
        assertEquals(ResearchRepairResult.Reason.UNKNOWN_BLOCK, reason(correction("B999-unknown", block.digest)))
        assertEquals(ResearchRepairResult.Reason.HASH_MISMATCH, reason(correction(block.id, "stale")))
        val preserved = ResearchReportRepair.blocks(original, packet).first()
        assertEquals(ResearchRepairResult.Reason.PRESERVED_BLOCK, reason(correction(preserved.id, preserved.digest)))
    }

    @Test fun unsupportedReplacementCannotBecomeAnAppliedReport() {
        val block = ResearchReportRepair.blocks(original, packet)[1]
        assertEquals(ResearchRepairResult.Reason.INVALID_REPLACEMENT, reason(correction(block.id, block.digest, "The trial cured 1000 participants. [S1]")))
    }

    @Test fun validCorrectionPreservesEveryUnchangedByte() {
        val block = ResearchReportRepair.blocks(original, packet)[1]
        val result = ResearchReportRepair.applyResult(original, correction(block.id, block.digest), packet) as ResearchRepairResult.Applied
        assertEquals(original.replace(bad, "Scores were 9 versus 14 points. [S1]") + "\n\n## References\n\n[S1] https://example.org/trial", result.text)
        assertTrue(result.text.startsWith(good))
        assertFalse(result.text.contains("999"))
        assertTrue(ResearchDeliveryValidator.validate(result.text, packet).accepted)
    }
}
