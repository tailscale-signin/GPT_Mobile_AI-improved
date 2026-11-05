package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Keep correlated roots, their full stack continuations and only their duplicate references. */
internal fun correlatedDiagnosticRecords(readLines: ((Sequence<String>) -> Unit) -> Unit, identifiers: Collection<String>, json: Json = Json): List<JsonElement> {
    val patterns = identifiers.filter { it.length >= 8 }.distinct().chunked(64).map { group ->
        Regex(group.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) })
    }
    if (patterns.isEmpty()) return emptyList()
    val retained = mutableSetOf<String>()
    val parents = mutableMapOf<String, String?>()
    val incidentByEvent = mutableMapOf<String, String>()
    val runByEvent = mutableMapOf<String, String>()
    val selectedRuns = mutableSetOf<String>()
    // The immutable export can be reopened. Do not retain unrelated messages, source or stacks.
    readLines { lines ->
        lines.forEach { line ->
            val row = json.parseToJsonElement(line) as? JsonObject ?: return@forEach
            if (row.text("kind") != "duplicate_reference_batch") {
                row.text("eventId")?.let { id ->
                    parents[id] = row.text("parentEventId")
                    row.text("incidentId")?.let { incidentByEvent[id] = it }
                    val runId = (row["fields"] as? JsonObject)?.text("runId")
                    if (runId != null) runByEvent[id] = runId
                    if (patterns.any { it.containsMatchIn(line) }) {
                        retained += id
                        runId?.let(selectedRuns::add)
                    }
                }
            }
        }
    }
    // Explicit incident association also connects separately timestamped Android
    // frames. Shared network incidents must not import another run's private rows.
    val children = parents.mapNotNull { (id, parent) -> parent?.let { it to id } }.groupBy({ it.first }, { it.second })
    val incidentMembers = incidentByEvent.entries.groupBy({ it.value }, { it.key })
    val expandedIncidents = mutableSetOf<String>()
    val pending = ArrayDeque(retained)
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        parents[id]?.takeIf(retained::add)?.let(pending::addLast)
        children[id].orEmpty().filter(retained::add).forEach(pending::addLast)
        incidentByEvent[id]?.takeIf(expandedIncidents::add)?.let { incident ->
            incidentMembers[incident].orEmpty().filter { candidate ->
                runByEvent[candidate]?.let { it in selectedRuns } != false
            }.filter(retained::add).forEach(pending::addLast)
        }
    }
    val result = mutableListOf<JsonElement>()
    readLines { lines ->
        lines.forEach { line ->
            val row = json.parseToJsonElement(line) as? JsonObject ?: return@forEach
            if (row.text("kind") == "duplicate_reference_batch") {
                val references = (row["references"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().filter { it.text("eventId") in retained }
                if (references.isNotEmpty()) result += JsonObject(row + mapOf("references" to JsonArray(references), "referenceCount" to JsonPrimitive(references.size)))
            } else if (row.text("eventId") in retained || patterns.any { it.containsMatchIn(line) }) {
                result += row
            }
        }
    }
    return result
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
