package dev.chungjungsoo.gptmobile.data.localmodel

internal fun importWorkspaceId(jobId: String?): String = jobId?.also {
    require(it.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid import job identity" }
} ?: java.util.UUID.randomUUID().toString()
