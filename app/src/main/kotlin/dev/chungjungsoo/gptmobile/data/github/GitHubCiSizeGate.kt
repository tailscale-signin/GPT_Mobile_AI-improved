package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The signed packaging job emits these four consecutive fields for each artifact. */
internal object GitHubCiSizeGate {
    private val timestamp = Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z\s+""")
    private val classField = Regex(""""budget_class"\s*:\s*"(universal|arm64-v8a|x86_64)"\s*,""")
    private val bytesField = Regex(""""bytes"\s*:\s*(\d+)\s*,""")
    private val limitField = Regex(""""budget_bytes"\s*:\s*(\d+)\s*,""")
    private val failed = Regex(""""passed"\s*:\s*false\s*,?""")

    fun find(lines: List<String>, firstLine: Int = 1): JsonObject? {
        for (index in 0 until lines.size - 3) {
            if ("budget_class" !in lines[index]) continue
            fun field(offset: Int) = lines[index + offset].replaceFirst(timestamp, "").trim()
            val edition = classField.matchEntire(field(0))?.groupValues?.get(1) ?: continue
            val bytes = bytesField.matchEntire(field(1))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val budget = limitField.matchEntire(field(2))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (bytes <= budget || !failed.matches(field(3))) continue
            return buildJsonObject {
                put("budget_class", edition)
                put("bytes", bytes)
                put("budget_bytes", budget)
                put("over_budget_bytes", bytes - budget)
                put("passed", false)
                put("start_line", firstLine + index)
                put("end_line", firstLine + index + 3)
            }
        }
        return null
    }

    fun fromExcerpts(rows: JsonArray?): JsonObject? {
        val lines = ArrayDeque<String>(4)
        var previous = 0
        for (item in rows.orEmpty()) {
            val row = item as? JsonObject ?: continue
            val line = (row["line"] as? JsonPrimitive)?.intOrNull ?: continue
            val offset = (row["start_offset"] as? JsonPrimitive)?.intOrNull
            if (line != previous + 1 || offset != null && offset != 0) lines.clear()
            previous = line
            if (line <= 0 || offset != null && offset != 0) continue
            if (lines.size == 4) lines.removeFirst()
            lines.addLast(row.text("text"))
            if (lines.size == 4 && "budget_class" in lines.first()) find(lines, line - 3)?.let { return it }
        }
        return null
    }

    fun observation(args: JsonObject, data: JsonObject): JsonObject? {
        val gate = data["apk_size_failure"] as? JsonObject ?: fromExcerpts(data["excerpts"] as? JsonArray) ?: return null
        val revision = data.text("head_sha").takeIf { GitHubInventoryQuery.commitSha(JsonPrimitive(it)) != null } ?: return null
        val run = (data["run_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        val attempt = (data["run_attempt"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return null
        val job = (data["job_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        if (listOf("expected_head_sha", "commit_sha", "head_sha").any { it in args && !args.text(it).equals(revision, true) } ||
            listOf("run_id", "run_attempt", "job_id").any { it in args && args.text(it) != data.text(it) }
        ) {
            return null
        }
        val bytes = (gate["bytes"] as? JsonPrimitive)?.longOrNull ?: return null
        val limit = (gate["budget_bytes"] as? JsonPrimitive)?.longOrNull ?: return null
        val start = (gate["start_line"] as? JsonPrimitive)?.intOrNull ?: return null
        if (limit < 0 ||
            bytes <= limit ||
            gate["over_budget_bytes"] != JsonPrimitive(bytes - limit) ||
            start <= 0 ||
            start > Int.MAX_VALUE - 3 ||
            gate["end_line"] != JsonPrimitive(start + 3) ||
            gate["passed"] != JsonPrimitive(false) ||
            gate.text("budget_class") !in setOf("universal", "arm64-v8a", "x86_64")
        ) {
            return null
        }
        return buildJsonObject {
            for (key in listOf("budget_class", "bytes", "budget_bytes", "over_budget_bytes", "passed", "start_line", "end_line")) put(key, gate.getValue(key))
            put("head_sha", revision)
            put("run_id", run)
            put("run_attempt", attempt)
            put("job_id", job)
        }
    }

    fun retained(receipts: Map<String, JsonObject>): Map<String, JsonObject> = buildMap {
        for ((id, row) in GitHubStatusEvidence.selected(receipts, null)) {
            if (row["trusted"] != JsonPrimitive(true) ||
                row["success"] != JsonPrimitive(true) ||
                row["verification_eligible"] == JsonPrimitive(false) ||
                row.text("tool").githubOperationFamily() != "github_ci"
            ) {
                continue
            }
            val args = row["arguments"] as? JsonObject ?: continue
            if (args.text("action") !in setOf("logs", "failure") || "view_cursor" in args) continue
            val data = GitHubStatusEvidence.data(row)
            val summary = row["semantic_summary"] as? JsonObject
            val stored = summary?.get("apk_size_failure") as? JsonObject
            val gate = data?.let { observation(args, it) } ?: stored?.let {
                observation(args, JsonObject(it + ("apk_size_failure" to it)))
            } ?: continue
            put(id, gate)
        }
    }

    fun describe(gate: JsonObject): String =
        "The ${gate.text("budget_class")} APK measured ${gate["bytes"]} bytes against a ${gate["budget_bytes"]}-byte limit, " +
            "an overage of ${gate["over_budget_bytes"]} bytes. Run ${gate["run_id"]}, attempt ${gate["run_attempt"]}, job ${gate["job_id"]}, " +
            "lines ${gate["start_line"]}–${gate["end_line"]}, records this failed packaging check at `${gate.text("head_sha")}`. " +
            "Inspect the APK contributions and the configured size budget. Verify any packaging change against the signed APK at its new commit."

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
