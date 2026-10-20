package dev.chungjungsoo.gptmobile.data.research.feature

import kotlinx.serialization.Serializable

@Serializable
enum class ScientificAppraisalMode { ADAPTIVE, EXPERT }

@Serializable
enum class AutoExamination { OFF, WHEN_NEEDED, ALL_ELIGIBLE }

@Serializable
data class ResearchFeatureSettings(
    val schemaVersion: Int = 1,
    val enabled: Boolean = true,
    val crawlerProfileUid: String? = null,
    val reviewerProfileUid: String? = null,
    val appraisalMode: ScientificAppraisalMode = ScientificAppraisalMode.ADAPTIVE,
    val peerReviewedOnly: Boolean = false,
    val archiveEnabled: Boolean = true,
    val archivePreflight: Boolean = true,
    val autoExamination: AutoExamination = AutoExamination.WHEN_NEEDED,
    val ordinaryChatAutoExamination: Boolean = false,
    val backgroundExamination: Boolean = false,
    val articleCards: Boolean = true,
    val compactCards: Boolean = true,
    val showMethodMetrics: Boolean = true,
    val showWarnings: Boolean = true,
    val targetedCrawl: Boolean = true,
    val comparisonLimit: Int = 8,
    val rubricVersion: Int = 1,
    val automaticOrganization: Boolean = true,
    val researchMemory: Boolean = false,
    val shareResearchRecall: Boolean = false,
    val unexaminedStudyLimit: Int = 1000
) {
    fun validated() = copy(archiveEnabled = true).apply { require(schemaVersion == 1 && comparisonLimit in 2..12 && rubricVersion == 1 && unexaminedStudyLimit in 1..50000) }
}

@Serializable
enum class ArticleCoverage { METADATA_ONLY, ABSTRACT_ONLY, PARTIAL_TEXT, FULL_TEXT_CAPTURED, FULL_ARTICLE_EXAMINED, UNAVAILABLE }

@Serializable
enum class PublicationReview { UNKNOWN, CONFIRMED, NOT_PEER_REVIEWED }

@Serializable
enum class Missingness { PRESENT, NOT_REPORTED, NOT_APPLICABLE, UNAVAILABLE, CONFLICTING }

@Serializable
enum class ScientificConcern { LOW, SOME, HIGH, UNKNOWN, NOT_APPLICABLE }

@Serializable
enum class StudyDesign { RANDOMIZED_TRIAL, OBSERVATIONAL, DIAGNOSTIC, SYSTEMATIC_REVIEW, QUALITATIVE, MACHINE_LEARNING, PROTOCOL, COMMENTARY, UNKNOWN }

@Serializable
enum class ExaminationStage { QUEUED, RESOLVING, READING, EXTRACTING, APPRAISING, REVIEWING, PAUSED, COMPLETE_FULL, COMPLETE_PARTIAL, ABSTRACT_ONLY, UNAVAILABLE, FAILED, CANCELED }

@Serializable
enum class ExaminationOrigin { USER_EXAMINE, AUTHORIZED_RESEARCH, AUTOMATIC_CHAT }
