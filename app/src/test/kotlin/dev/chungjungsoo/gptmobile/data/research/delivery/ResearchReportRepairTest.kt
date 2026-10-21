package dev.chungjungsoo.gptmobile.data.research.delivery

import dev.chungjungsoo.gptmobile.data.model.ResearchReportDetail
import dev.chungjungsoo.gptmobile.data.research.ResearchSnapshot
import dev.chungjungsoo.gptmobile.data.research.ResearchSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchReportRepairTest {
    private val passage = "The randomized trial enrolled 20 participants. Scores were 9 versus 14 points. Follow-up was short."
    private val source = ResearchSource("S1", "https://example.org/trial", "Sleep trial", "Read", passage, versionId = "v1")
    private val packet = ResearchDeliveryPacket.build(ResearchSnapshot("sleep trial", sources = listOf(source)), ResearchReportDetail.BRIEF)
    private val good = "The randomized trial enrolled 20 participants. [S1]"
    private val bad = "The trial cured 999 participants. [S1]"
    private val original = "$good\n\n$bad\n\nPartial coverage. Independent review is not available."

    private fun replacement(block: ResearchReportRepair.Block, text: String = "Scores were 9 versus 14 points. [S1]", digest: String = block.digest) = buildJsonObject {
        put("block_id", block.id)
        put("previous_sha256", digest)
        put("text", text)
    }

    @Test fun correctsOnlyRejectedParagraphAndPreservesAllOtherBytes() {
        val blocks = ResearchReportRepair.blocks(original, packet)
        assertTrue(blocks.first().issues.isEmpty())
        assertTrue(blocks[1].issues.contains("NUMERICAL_CLAIM_NOT_IN_CITED_PASSAGE"))
        val correction = buildJsonObject {
            put("replacements", JsonArray(listOf(replacement(blocks[1]))))
            put("append", "## References\n\n[S1] https://example.org/trial")
        }.toString()
        val result = ResearchReportRepair.apply(original, correction, packet)
        assertEquals(original.replace(bad, "Scores were 9 versus 14 points. [S1]") + "\n\n## References\n\n[S1] https://example.org/trial", result)
        assertTrue(ResearchDeliveryValidator.validate(requireNotNull(result), packet).issues.isEmpty())
        assertFalse(result.contains("999"))
    }

    @Test fun rejectsReplacementOfAnAcceptedParagraphAndStaleOrDuplicateAnchors() {
        val blocks = ResearchReportRepair.blocks(original, packet)
        for (changes in listOf(
            listOf(replacement(blocks.first())),
            listOf(replacement(blocks[1], digest = "stale")),
            listOf(replacement(blocks[1]), replacement(blocks[1]))
        )) {
            val reply = buildJsonObject {
                put("replacements", JsonArray(changes))
                put("append", "")
            }.toString()
            assertNull(ResearchReportRepair.apply(original, reply, packet))
        }
    }

    @Test fun rejectsNewUnsupportedNumbersUnknownCitationsAndInterruptedJson() {
        val block = ResearchReportRepair.blocks(original, packet)[1]
        for (text in listOf("The trial cured 1000 participants. [S1]", "The trial enrolled 20 participants. [S99]")) {
            val reply = buildJsonObject {
                put("replacements", JsonArray(listOf(replacement(block, text))))
                put("append", "")
            }.toString()
            assertNull(ResearchReportRepair.apply(original, reply, packet))
            assertNull(ResearchReportRepair.apply(original, reply.dropLast(8), packet))
        }
    }
}
