package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "research_source_validations", indices = [Index("versionId")])
data class ResearchSourceValidation(@PrimaryKey val validationId: String, val versionId: String, val bodyHash: String, val validatorRevision: Int, val kind: String, val reasonsJson: String, val previousCoverage: ArticleCoverage, val workKind: String, val classifiedAt: Long)

@Serializable
@Entity(tableName = "research_identity_resolutions", indices = [Index("workId"), Index("relatedWorkId")])
data class ResearchIdentityResolution(@PrimaryKey val resolutionId: String, val workId: String, val relatedWorkId: String, val canonicalWorkId: String, val authorityScope: String, val relation: String, val evidence: String, val disposition: String, val recordedAt: Long)

@Entity(tableName = "research_repair_cursors")
data class ResearchRepairCursor(@PrimaryKey val kind: String, val position: String, val revision: Int, val updatedAt: Long)
