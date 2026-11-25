package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
internal fun GitHubSourceDeliveryDetails(trace: JsonObject?) {
    val attempts = (trace?.get("attempts") as? JsonArray).orEmpty()
    if (attempts.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide GitHub source evidence" else "Show GitHub source evidence")
    }
    if (!expanded) return
    val detail = remember(trace) {
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
        buildString {
            appendLine("Each attempt shows its serialized tool output. Match the tool-call digest, revision and lines against a verified excerpt in the answer. Other claim attribution is unknown.")
            for (attempt in attempts.filterIsInstance<JsonObject>()) {
                appendLine()
                appendLine("Attempt " + attempt.text("physical_attempt_id"))
                appendLine(
                    when (attempt.text("transport")) {
                        "REQUEST_BODY_SENT" -> "HTTP request body sent"
                        "PROVEN_UNSENT" -> "Transport confirmed the request was unsent"
                        else -> "Request serialized. Transmission unverified."
                    }
                )
                for (row in (attempt["ranges"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
                    appendLine("Tool-call digest " + row.text("call_id_sha256"))
                    val expected = row["prepared"] as? JsonObject
                    appendLine(
                        when (row.text("state")) {
                            "MATCHED" -> "Retained excerpt matched the serialized tool output"
                            "MATCHED_FRAGMENTS" -> "Matching fragments of the retained excerpt"
                            "TOOL_OUTPUT_OMITTED" -> "Tool output omitted from serialized tool messages"
                            "NO_MATCHING_SOURCE_RANGE" -> "Tool output present without a matching excerpt"
                            "UNKNOWN_CALL_ID" -> "Tool output could not be matched to a call"
                            else -> "Source range unknown"
                        }
                    )
                    if (expected != null) {
                        appendLine("Retained lines " + expected.text("start_line") + "–" + expected.text("end_line"))
                        appendLine("Commit " + expected.text("commit_sha") + " · blob " + expected.text("blob_sha"))
                        appendLine("Path digest " + expected.text("path_sha256"))
                    }
                    val fragments = (row["serialized"] as? JsonArray).orEmpty()
                    for (part in fragments.filterIsInstance<JsonObject>()) {
                        appendLine(
                            "Serialized lines " + part.text("start_line") + "–" + part.text("end_line") +
                                " · UTF-16 offset " + part.text("offset_utf16") + ", length " + part.text("length_utf16")
                        )
                        if (part.text("partial_source") == "true") appendLine("Partial source excerpt")
                        appendLine("Excerpt digest " + part.text("excerpt_sha256"))
                    }
                    if ((row.text("serialized_fragments")?.toIntOrNull() ?: 0) > fragments.size) appendLine("Additional matching fragments omitted from this trace")
                }
                if (attempt.text("inspection_limited") == "true") appendLine("Source inspection reached its range limit")
            }
            val omitted = trace?.text("attempts_omitted")?.toIntOrNull() ?: 0
            if (omitted > 0) appendLine("Additional attempts omitted from this trace · $omitted")
        }.trimEnd()
    }
    SelectionContainer { Text(detail, style = MaterialTheme.typography.bodySmall) }
}
