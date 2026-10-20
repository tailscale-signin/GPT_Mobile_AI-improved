package dev.chungjungsoo.gptmobile.data.research.delivery

internal enum class ResearchReportBlockKind { HEADING, FINDING, LIMITATION, RECOMMENDATION, UNREAD_LEAD, REFERENCE, TEXT }
internal data class ResearchReportBlock(val kind: ResearchReportBlockKind, val text: String, val sourceIds: Set<String>)

/** Typed paragraph contracts sit between Markdown rendering and publication. They do not claim entailment. */
internal object ResearchReportBlocks {
    fun parse(text: String, packet: ResearchDeliveryPacket): List<ResearchReportBlock> {
        var references = false
        return text.split(Regex("\n\\s*\n")).map { paragraph ->
            val heading = paragraph.trim().startsWith('#') && paragraph.lines().size == 1
            if (heading && Regex("(?i)^#{1,6}\\s+references\\s*$").matches(paragraph.trim())) references = true
            val kind = when {
                heading -> ResearchReportBlockKind.HEADING
                references -> ResearchReportBlockKind.REFERENCE
                Regex("(?i)unread|metadata.only|not read|retrieval failed").containsMatchIn(paragraph) -> ResearchReportBlockKind.UNREAD_LEAD
                Regex("(?i)unknown|unverified|limited|uncertain|could not|not established|not verified").containsMatchIn(paragraph) -> ResearchReportBlockKind.LIMITATION
                Regex("(?i)recommend|should|consider|next step").containsMatchIn(paragraph) -> ResearchReportBlockKind.RECOMMENDATION
                ResearchReportReferences.cited(paragraph, packet).isNotEmpty() -> ResearchReportBlockKind.FINDING
                else -> ResearchReportBlockKind.TEXT
            }
            ResearchReportBlock(kind, paragraph, ResearchReportReferences.cited(paragraph, packet))
        }
    }
}
