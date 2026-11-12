package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Host validation shared by native callbacks and ordinary tools. Never guesses identifiers. */
internal object ToolArgumentValidator {
    fun errors(arguments: JsonObject, schema: JsonObject): List<String> = validate(arguments, schema, "$", 0).take(12)

    /** Give the model the failed constraint, never a second copy of the whole tool catalog. */
    fun repairMessage(toolName: String, errors: List<String>, schema: JsonObject, arguments: JsonObject? = null): String {
        val guidance = when (toolName.substringBefore("__")) {
            "github_write" ->
                "Each files item must contain its own non-empty repository-relative path. " +
                    "For a replacement supply path and complete content; for a deletion supply path, delete=true and the observed expected_blob_sha. " +
                    "Use the observed expected_head_sha and the observed blob SHA for existing files. Find missing paths and SHAs with GitHub read/search; do not guess them. "
            "github_pr" -> if (arguments?.get("action") != JsonPrimitive("list") && errors.any { "pull_number" in it }) {
                "Use action=list to discover an existing pull_number before headers, context, comments or reviews. " +
                    "Use the exact field name pull_number, not number or pr_number. Writes need the observed expected_head_sha. "
            } else {
                ""
            }
            "github_search" ->
                "Use the search schema for a new query. For another page, use the last result's exact continuation_call. "
            else -> ""
        }
        val declared = (schema["properties"] as? JsonObject).orEmpty().keys.sorted().joinToString(", ").take(1000)
        return "Invalid arguments. " + errors.joinToString("; ").take(3000) +
            ". No action was dispatched. " + guidance + "Submit one corrected proposal with a new call ID. " +
            "Declared fields: " + declared
    }

    /** A safe read recipe is constructed from the actual registered schema; no ID is inferred. */
    fun rejection(callId: String, toolName: String, arguments: JsonObject, schema: JsonObject, validationErrors: List<String> = errors(arguments, schema)): AgentToolResult {
        val correction = discoveryCorrection(toolName, arguments, schema)
        val selector = selectorProblem(toolName, arguments)
        return AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "gptmobile.arguments.v2")
                    put("operation", toolName)
                    put("message", repairMessage(toolName, validationErrors, schema, arguments))
                    put("validation_errors", JsonArray(validationErrors.map(::JsonPrimitive)))
                    put("state", "PRECHECK_REJECTED")
                    put("dispatched", false)
                    put("retryable", false)
                    put("corrected_proposal_limit", 1)
                    if (selector != null) {
                        put("reason", selector.reason)
                        put(
                            if (selector.missing) "missing_fields" else "invalid_fields",
                            JsonArray(listOf(JsonPrimitive(selector.field)))
                        )
                        put(
                            "required_selector",
                            buildJsonObject {
                                put("field", selector.field)
                                put("type", "positive_integer")
                                put("source", selector.source)
                                put("discovery_action", "list")
                            }
                        )
                    }
                    if (correction != null) {
                        put(
                            "correction_call",
                            buildJsonObject {
                                put("tool", toolName)
                                put("arguments", correction)
                            }
                        )
                    }
                }
            ),
            true,
            errorCode = "INVALID_ARGUMENTS",
            dispatched = false,
            executionReceipt = rejectionReceipt(arguments, schema, validationErrors, selector)
        )
    }

    /** Local machine-readable cause, separate from the unchanged approved public-report contract. */
    private fun rejectionReceipt(arguments: JsonObject, schema: JsonObject, validationErrors: List<String>, selector: SelectorProblem?): JsonObject {
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val missing = (schema["required"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>()
            .filter { it.isString && it.content !in arguments }.map { it.content }.toMutableSet()
        val invalid = properties.mapNotNull { (field, raw) ->
            val value = arguments[field] ?: return@mapNotNull null
            val constraint = raw as? JsonObject ?: return@mapNotNull null
            field.takeIf { validate(value, constraint, "$", 0).isNotEmpty() }
        }.toMutableSet()
        selector?.let { if (it.missing) missing += it.field else invalid += it.field }
        fun fieldNames(fields: Set<String>) = JsonArray(
            fields.filter { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }.sorted().take(12).map(::JsonPrimitive)
        )
        return buildJsonObject {
            put("failed_stage", "tool_precheck")
            put("dispatched", false)
            put("retryable", false)
            put("requires_reconciliation", false)
            put("reason_code", selector?.reason ?: if (errors(arguments, schema).isEmpty()) "TOOL_ARGUMENT_CONSTRAINT_REJECTED" else "TOOL_ARGUMENT_SCHEMA_REJECTED")
            put("validation_error_count", validationErrors.size)
            put("validation_field_scope", "TOP_LEVEL_AND_KNOWN_SELECTOR")
            if (missing.isNotEmpty()) put("missing_fields", fieldNames(missing))
            if (invalid.isNotEmpty()) put("invalid_fields", fieldNames(invalid))
            if (schema["additionalProperties"] == JsonPrimitive(false)) put("unknown_field_count", arguments.keys.count { it !in properties })
        }
    }

    private fun discoveryCorrection(toolName: String, arguments: JsonObject, schema: JsonObject): JsonObject? {
        val operation = toolName.substringBefore("__")
        if (operation == "github_search" && "per_page" in arguments && arguments.keys.none { it in setOf("view_cursor", "index_cursor", "search_id") }) {
            val proposal = JsonObject(arguments - "per_page")
            return proposal.takeIf { errors(it, schema).isEmpty() }
        }
        if (operation !in setOf("github_pr", "github_issue") || "view_cursor" in arguments) return null
        val issue = operation == "github_issue"
        val numberKey = if (issue) "issue_number" else "pull_number"
        val action = (arguments["action"] as? JsonPrimitive)?.content ?: "context"
        val readActions = if (issue) setOf("headers", "context", "comments", "history") else setOf("headers", "context", "comments", "reviews", "threads")
        if (action !in readActions) return null
        val selector = arguments[numberKey]
        if ((selector as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { it > 0 } == true) return null
        val aliases = (if (issue) listOf("number") else listOf("pr_number", "number", "pull_request_number")).filter { it in arguments }
        if (selector == null && aliases.size == 1) {
            val alias = aliases.single()
            val proposal = JsonObject(arguments - alias + (numberKey to arguments.getValue(alias)))
            if (errors(proposal, schema).isEmpty()) return proposal
        }
        // Preserve an explicit complete repository. An incomplete selector must be repaired first.
        val owner = arguments["owner"]
        val repo = arguments["repo"]
        if ((owner == null) != (repo == null)) return null
        val proposal = buildJsonObject {
            put("action", "list")
            if (issue) arguments["state"]?.let { put("state", it) }
            if (owner != null && repo != null) {
                put("owner", owner)
                put("repo", repo)
            }
        }
        return proposal.takeIf { errors(it, schema).isEmpty() }
    }

    private data class SelectorProblem(val field: String, val missing: Boolean, val reason: String, val source: String)

    /** Missing and malformed read selectors need a typed discovery path, not prose parsing. */
    private fun selectorProblem(toolName: String, arguments: JsonObject): SelectorProblem? {
        val operation = toolName.substringBefore("__")
        if (operation !in setOf("github_pr", "github_issue") || "view_cursor" in arguments) return null
        val issue = operation == "github_issue"
        val action = (arguments["action"] as? JsonPrimitive)?.content ?: if (issue) "list" else "context"
        val readActions = if (issue) setOf("headers", "context", "comments", "history") else setOf("headers", "context", "comments", "reviews", "threads")
        if (action !in readActions) return null
        val field = if (issue) "issue_number" else "pull_number"
        val value = arguments[field]
        if ((value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { it > 0 } == true) return null
        return SelectorProblem(field, value == null, if (issue) "ISSUE_SELECTOR_REQUIRED" else "PR_SELECTOR_REQUIRED", if (issue) "OBSERVED_GITHUB_ISSUE" else "OBSERVED_GITHUB_PR")
    }

    private fun discriminatorScore(value: JsonElement, option: JsonObject): Int {
        if (value !is JsonObject) return 0
        val properties = option["properties"] as? JsonObject ?: return 0
        var score = 0
        for ((key, raw) in properties) {
            val constraint = raw as? JsonObject ?: continue
            val actual = value[key] ?: continue
            val expected = constraint["const"]
            val choices = constraint["enum"] as? JsonArray
            if (expected != null && actual != expected || choices != null && actual !in choices) return -1
            if (expected != null || choices != null) score++
        }
        // With no action, prefer the advertised default branch over cursor-only discovery.
        if ("action" !in value &&
            "action" in properties &&
            (option["required"] as? JsonArray)?.contains(JsonPrimitive("action")) != true
        ) {
            score++
        }
        return score
    }

    private fun validate(value: JsonElement, schema: JsonObject, path: String, depth: Int): List<String> {
        if (depth > 24) return listOf("$path: schema nesting exceeds validation limit")
        val errors = mutableListOf<String>()
        fun matches(type: String): Boolean = when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.content.toBigDecimalOrNull()?.let { it.stripTrailingZeros().scale() <= 0 } == true
            "number" -> value is JsonPrimitive && !value.isString && value.content.toBigDecimalOrNull() != null
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "null" -> value == JsonNull
            else -> false
        }
        val types = when (val type = schema["type"]) {
            is JsonPrimitive -> listOf(type.content)
            is JsonArray -> type.filterIsInstance<JsonPrimitive>().map { it.content }
            else -> emptyList()
        }
        if (types.isNotEmpty() && types.none(::matches)) return listOf("$path: expected ${types.joinToString(" or ")}")
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (value !in allowed) {
                errors += "$path: unsupported value; allowed: ${allowed.take(20).joinToString().take(500)}"
            }
        }
        schema["const"]?.let { if (value != it) errors += "$path: must equal the declared constant" }
        for (kind in listOf("allOf", "anyOf", "oneOf")) {
            (schema[kind] as? JsonArray)?.filterIsInstance<JsonObject>()?.let { options ->
                val failures = options.map { validate(value, it, path, depth + 1) }
                val matching = failures.count { it.isEmpty() }
                when {
                    kind == "allOf" -> failures.forEachIndexed { index, failure ->
                        failure.take(4).forEach { errors += "$path: allOf[$index]: $it" }
                    }
                    matching == 0 -> {
                        val scores = options.map { discriminatorScore(value, it) }
                        val bestScore = scores.maxOrNull() ?: 0
                        val nearest = options.indices.filter { scores[it] == bestScore }
                            .sortedBy { failures[it].size }.take(2)
                        if (nearest.isEmpty()) errors += "$path: $kind has no declared alternatives"
                        nearest.forEach { index ->
                            failures[index].take(4).forEach { errors += "$path: $kind[$index]: $it" }
                        }
                    }
                    kind == "oneOf" && matching != 1 -> errors += "$path: oneOf matches $matching branches; supply exactly one allowed argument form"
                }
            }
        }
        if (value is JsonObject) {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (schema["required"] as? JsonArray)?.filterIsInstance<JsonPrimitive>()?.forEach { if (it.content !in value) errors += "$path.${it.content}: required field missing" }
            value.forEach { (key, child) ->
                val childSchema = properties[key] as? JsonObject ?: schema["additionalProperties"] as? JsonObject
                if (childSchema != null) {
                    errors += validate(child, childSchema, "$path.$key", depth + 1)
                } else if (schema["additionalProperties"] == JsonPrimitive(false) && key !in properties) {
                    errors += "$path.$key: undeclared field"
                }
            }
        }
        if (value is JsonArray) {
            (schema["minItems"] as? JsonPrimitive)?.intOrNull?.let { if (value.size < it) errors += "$path: too few items" }
            (schema["maxItems"] as? JsonPrimitive)?.intOrNull?.let { if (value.size > it) errors += "$path: too many items" }
            (schema["items"] as? JsonObject)?.let { item -> value.forEachIndexed { index, child -> errors += validate(child, item, "$path[$index]", depth + 1) } }
        }
        if (value is JsonPrimitive && value.isString) {
            (schema["minLength"] as? JsonPrimitive)?.intOrNull?.let { if (value.content.length < it) errors += "$path: too short" }
            (schema["maxLength"] as? JsonPrimitive)?.intOrNull?.let { if (value.content.length > it) errors += "$path: too long" }
            (schema["pattern"] as? JsonPrimitive)?.contentOrNull?.let { pattern ->
                if (runCatching { Regex(pattern).containsMatchIn(value.content) }.getOrDefault(false).not()) errors += "$path: pattern mismatch"
            }
        }
        if (value is JsonPrimitive && !value.isString) {
            value.content.toBigDecimalOrNull()?.let { number ->
                (schema["minimum"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.let { if (number < it) errors += "$path: below minimum" }
                (schema["maximum"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.let { if (number > it) errors += "$path: above maximum" }
            }
        }
        return errors
    }
}
