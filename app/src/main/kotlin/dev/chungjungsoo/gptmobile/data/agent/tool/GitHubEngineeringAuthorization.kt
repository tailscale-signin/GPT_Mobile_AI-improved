package dev.chungjungsoo.gptmobile.data.agent.tool

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal fun engineeringDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal data class EngineeringFilePatch(val path: String, val before: String, val after: String, val delete: Boolean = false)

internal object GitHubEngineeringAuthorization {
    fun patchJson(files: List<EngineeringFilePatch>): String {
        require(files.size in 1..20 && files.map { it.path }.distinct().size == files.size)
        require(files.any { it.delete || it.before != it.after })
        require(files.sumOf { it.before.toByteArray().size.toLong() + it.after.toByteArray().size } <= 8L * 1024 * 1024)
        return JSONArray().apply {
            files.sortedBy { it.path }.forEach {
                dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient.filePath(it.path)
                require(it.after.toByteArray().size <= 2 * 1024 * 1024 && '\u0000' !in it.after)
                require(!it.delete || it.after.isEmpty()) { "A deletion cannot include replacement content" }
                val existingLines = it.before.lineSequence().toHashSet()
                val added = it.after.lineSequence().filter { line -> line !in existingLines }.joinToString("\n")
                require(!Regex("(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)").containsMatchIn(added)) { "Patch contains a credential; remove it before review" }
                put(
                    JSONObject().put("path", it.path).put("before", it.before).put("after", it.after).apply {
                        // Preserve the canonical representation and approvals of existing replacement jobs.
                        if (it.delete) put("delete", true)
                    }
                )
            }
        }.toString()
    }

    fun files(json: String): List<EngineeringFilePatch> = JSONArray(json).let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).let { row -> EngineeringFilePatch(row.getString("path"), row.getString("before"), row.getString("after"), row.optBoolean("delete", false)) } }
    }

    fun digest(task: EngineeringTaskRow, run: EngineeringRunRow): String = engineeringDigest(
        listOf(task.taskId, task.repositoryOwner, task.repositoryName, task.workingBranch, task.baseSha, task.attempt.toString(), run.connectionUid, run.patchJson, run.commitMessage, run.policyBranch).joinToString("\u0000")
    )

    fun requireApproval(task: EngineeringTaskRow, run: EngineeringRunRow, actor: String, now: Long) {
        require(run.taskId == task.taskId && run.patchDigest == digest(task, run)) { "Patch or destination changed; review again" }
        require(task.state in setOf(EngineeringState.COMMITTING.name, EngineeringState.AWAITING_APPROVAL.name))
        require(!run.approvalRevoked && run.approvedActor == actor && actor.isNotBlank()) { "Approval was revoked or belongs to a different GitHub account" }
        val approvedAt = requireNotNull(run.approvedAtEpochMs) { "Approve the patch first" }
        val expiresAt = requireNotNull(run.approvalExpiresAtEpochMs)
        require(now in approvedAt until expiresAt && expiresAt - approvedAt in 1..30 * 60_000L) { "Approval expired; review this exact patch again" }
    }
}
