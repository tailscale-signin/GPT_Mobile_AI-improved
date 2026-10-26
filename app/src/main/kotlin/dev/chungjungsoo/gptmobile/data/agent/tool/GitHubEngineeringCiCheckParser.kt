package dev.chungjungsoo.gptmobile.data.agent.tool

import org.json.JSONObject

/** Parses GitHub check-runs; only explicit successful conclusions may pass policy. */
internal object GitHubEngineeringCiCheckParser {
    fun parse(json: String, expectedSha: String): List<EngineeringCheckReceipt> {
        val root = JSONObject(json)
        val runs = root.getJSONArray("check_runs")
        return buildList {
            for (index in 0 until runs.length()) {
                val run = runs.getJSONObject(index)
                val head = run.optString("head_sha")
                if (head != expectedSha) continue
                val status = when (run.optString("status")) {
                    "completed" -> EngineeringCheckStatus.COMPLETED
                    "in_progress" -> EngineeringCheckStatus.IN_PROGRESS
                    else -> EngineeringCheckStatus.QUEUED
                }
                val conclusion = when (run.optString("conclusion")) {
                    "success" -> EngineeringCheckConclusion.SUCCESS
                    "failure" -> EngineeringCheckConclusion.FAILURE
                    "cancelled" -> EngineeringCheckConclusion.CANCELLED
                    "timed_out" -> EngineeringCheckConclusion.TIMED_OUT
                    "action_required" -> EngineeringCheckConclusion.ACTION_REQUIRED
                    "neutral" -> EngineeringCheckConclusion.NEUTRAL
                    "skipped" -> EngineeringCheckConclusion.SKIPPED
                    else -> EngineeringCheckConclusion.UNKNOWN
                }
                add(
                    EngineeringCheckReceipt(
                        run.getString("name"),
                        status,
                        conclusion,
                        head,
                        run.optLong("id").takeIf { it > 0 },
                        run.optJSONObject("app")?.optLong("id")?.takeIf { it > 0 },
                        startedAt = run.optString("started_at")
                    )
                )
            }
        }
    }
}
