package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Fixed-shape declarations only. Labels, endpoints, arbitrary build keys and bodies stay private. */
internal fun safeAuditRemoteIdentity(identity: AppAuditRemoteIdentity?): AppAuditRemoteIdentity? = identity?.copy(
    version = identity.version?.takeIf { it.matches(Regex("[0-9]{1,5}(\\.[0-9]{1,5}){1,3}")) },
    instanceId = identity.instanceId?.takeIf { it.matches(Regex("[a-f0-9]{32}")) },
    sourceCommit = identity.sourceCommit?.takeIf { it.matches(Regex("[a-f0-9]{40}")) },
    buildStatus = identity.buildStatus?.takeIf { it in setOf("development", "packaged") }
)?.takeIf { it.version != null || it.instanceId != null || it.sourceCommit != null || it.buildStatus != null }

/** Shared by the receipt export and per-check qualification plan. Never grant execution proof. */
internal fun appAuditRemoteIdentityJson(identity: AppAuditRemoteIdentity?): JsonObject? = safeAuditRemoteIdentity(identity)?.let {
    buildJsonObject {
        put("service", it.service.name)
        put("source", it.source.name)
        put("version", it.version)
        put("instanceId", it.instanceId)
        put("sourceCommit", it.sourceCommit)
        put("buildStatus", it.buildStatus)
        put("meaning", "Identity declared by the remote capability response; remote source and execution are not independently verified.")
    }
}
