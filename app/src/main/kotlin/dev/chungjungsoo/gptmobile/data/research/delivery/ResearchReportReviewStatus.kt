package dev.chungjungsoo.gptmobile.data.research.delivery

internal object ResearchReportReviewStatus {
    fun status(packet: ResearchDeliveryPacket): ResearchReviewStatus {
        val claims = packet.claims.filter {
            it.exactQuoteVerified && it.relevance != "Irrelevant" && it.verdict in setOf("Supported", "Contradicted", "Insufficient")
        }
        val reviewed = claims.count { it.independentReview }
        return when {
            claims.isEmpty() || reviewed == 0 -> ResearchReviewStatus.UNREVIEWED
            reviewed == claims.size -> ResearchReviewStatus.REVIEWED
            else -> ResearchReviewStatus.PARTIALLY_REVIEWED
        }
    }

    fun disclose(text: String, packet: ResearchDeliveryPacket): String {
        val status = status(packet)
        val disclosure = when (status) {
            ResearchReviewStatus.REVIEWED -> "Review status: Reviewed; all retained source-bound claims have independent review."
            ResearchReviewStatus.PARTIALLY_REVIEWED -> "Review status: Partially reviewed; some retained claims have independent review and others remain unreviewed."
            ResearchReviewStatus.UNREVIEWED -> "Review status: Unreviewed; independent review is not established for retained claims."
        }
        val prior = Regex("(?im)^Review status:.*(?:\\r?\\n|$)")
        return if (prior.containsMatchIn(text)) prior.replace(text, disclosure + "\n") else "$disclosure\n\n$text"
    }
}
