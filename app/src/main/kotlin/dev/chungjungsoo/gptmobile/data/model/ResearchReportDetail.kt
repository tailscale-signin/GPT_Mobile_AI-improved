package dev.chungjungsoo.gptmobile.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ResearchReportDetail(val label: String, val wordTarget: String, val studyLimit: Int, val packetTokens: Int) {
    BRIEF("Brief overview", "300–600", 6, 4000),
    DETAILED("Detailed research", "1200–1800", 16, 10000),
    COMPREHENSIVE("Comprehensive scientific report", "2000–3000", 28, 16000)
}
