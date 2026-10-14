package dev.chungjungsoo.gptmobile.data.research.feature

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "research_discoveries", foreignKeys = [ForeignKey(entity = ResearchWork::class, parentColumns = ["workId"], childColumns = ["workId"], onDelete = ForeignKey.CASCADE)], indices = [Index("workId")])
data class ResearchDiscovery(@PrimaryKey val discoveryId: String, val workId: String, val engineId: String, val providerRecordId: String, val metadataJson: String, val observedAt: Long)

@Serializable
@Entity(tableName = "research_outcomes", foreignKeys = [ForeignKey(entity = ResearchWorkVersion::class, parentColumns = ["versionId"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("versionId"), Index("workId")])
data class ResearchOutcome(@PrimaryKey val outcomeId: String, val workId: String, val versionId: String, val outcomeKey: String, val reported: String, val limitations: String, val passageIdsJson: String, val state: String, val createdAt: Long, val producerVersion: String = "appraisal-v1")

@Serializable
@Entity(tableName = "research_recall_exclusions")
data class ResearchRecallExclusion(@PrimaryKey val workId: String, val excludedAt: Long)

@Serializable
@Entity(tableName = "research_handoffs")
data class ResearchHandoff(@PrimaryKey val handoffId: String, val query: String, val status: String, val createdAt: Long)

data class ResearchTopicCount(val topicId: String, val count: Int)
