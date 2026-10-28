package dev.chungjungsoo.gptmobile.data.agent.tool

import org.json.JSONArray
import org.json.JSONObject

/** Complete bounded pagination; an incomplete collection can never establish a pass. */
internal class GitHubEngineeringCiHttpGateway(tokenProvider: suspend () -> String?, private val api: GitHubEngineeringApi = GitHubEngineeringApi(tokenProvider)) : GitHubEngineeringCiReceiptGateway {

    override suspend fun fetch(repositoryOwner: String, repositoryName: String, commitSha: String): List<EngineeringCheckReceipt> {
        require(commitSha.matches(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")))
        val root = GitHubEngineeringApi.root(repositoryOwner, repositoryName)
        val checks = mutableListOf<EngineeringCheckReceipt>()
        var completed = false
        var expectedCount: Int? = null
        val runIds = mutableSetOf<Long>()
        for (page in 1..50) {
            val json = api.request("$root/commits/$commitSha/check-runs?filter=latest&per_page=100&page=$page")
            val envelope = JSONObject(json)
            val count = envelope.getInt("total_count")
            require(count in 0..5000)
            check(expectedCount == null || expectedCount == count) { "Check collection changed during pagination; retry inspection" }
            expectedCount = count
            val rows = envelope.getJSONArray("check_runs")
            for (i in 0 until rows.length()) check(runIds.add(rows.getJSONObject(i).getLong("id"))) { "Duplicate check-run page" }
            checks += GitHubEngineeringCiCheckParser.parse(json, commitSha)
            if (runIds.size == count) {
                completed = true
                break
            }
            check(rows.length() == 100 && runIds.size < count) { "Incomplete check-run collection" }
        }
        check(completed) { "Check-run pagination limit exceeded" }
        completed = false
        val statusIds = mutableSetOf<Long>()
        for (page in 1..50) {
            val rows = JSONArray(api.request("$root/commits/$commitSha/statuses?per_page=100&page=$page"))
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                check(statusIds.add(row.getLong("id"))) { "Duplicate commit-status page" }
                val state = row.getString("state")
                checks += EngineeringCheckReceipt(
                    row.getString("context"),
                    if (state == "pending") EngineeringCheckStatus.IN_PROGRESS else EngineeringCheckStatus.COMPLETED,
                    when (state) {
                        "success" -> EngineeringCheckConclusion.SUCCESS
                        "failure", "error" -> EngineeringCheckConclusion.FAILURE
                        else -> EngineeringCheckConclusion.UNKNOWN
                    },
                    commitSha,
                    row.getLong("id"),
                    source = "status",
                    startedAt = row.optString("created_at")
                )
            }
            if (rows.length() < 100) {
                completed = true
                break
            }
        }
        check(completed) { "Commit-status pagination limit exceeded" }
        return checks
    }

    /** GitHub branch protection plus active rulesets. Permission failures stay unavailable. */
    suspend fun requiredChecks(owner: String, repository: String, branch: String): Set<EngineeringRequiredCheck> {
        val root = GitHubEngineeringApi.root(owner, repository)
        val encoded = GitHubEngineeringApi.segment(branch)
        val required = mutableSetOf<EngineeringRequiredCheck>()
        try {
            val protection = JSONObject(api.request("$root/branches/$encoded/protection/required_status_checks"))
            val checks = protection.optJSONArray("checks")
            if (checks != null) {
                for (i in 0 until checks.length()) {
                    val check = checks.getJSONObject(i)
                    required += EngineeringRequiredCheck(check.getString("context"), check.optLong("app_id").takeIf { it > 0 })
                }
            }
            val contexts = protection.optJSONArray("contexts") ?: JSONArray()
            for (i in 0 until contexts.length()) {
                val name = contexts.getString(i)
                if (required.none { it.name == name }) required += EngineeringRequiredCheck(name)
            }
        } catch (error: EngineeringApiException) {
            if (error.status != 404) throw error
            // 404 may also mask missing permissions. The rules API must succeed,
            // and an empty aggregate remains pending rather than waived.
        }
        val rules = JSONArray(api.request("$root/rules/branches/$encoded"))
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optString("type") != "required_status_checks") continue
            val checks = rule.getJSONObject("parameters").getJSONArray("required_status_checks")
            for (j in 0 until checks.length()) {
                val check = checks.getJSONObject(j)
                required += EngineeringRequiredCheck(check.getString("context"), check.optLong("integration_id").takeIf { it > 0 })
            }
        }
        return required
    }
}
