package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.github.githubOperationFamily
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
        val guidance = when (toolName.githubOperationFamily()) {
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
            "github_read" ->
                "Use one to eight ranges with a repository-relative path and start_line>=1. " +
                    "Choose either max_lines (1..2000) or end_line, never both; omit both for a bounded default read. end_line=-1 means bounded EOF. "
            "github_ci" ->
                "Use only the declared fields for the selected action. Runs filters use status; returned runs include conclusion. " +
                    "For failed runs set status=failure. For logs use an observed job_id and positive ordered start_line/end_line spanning at most 2000 lines; read longer logs in successive windows. "
            "github_context" ->
                "For saved content use only the exact view_cursor from continuation_call. " +
                    "For a fresh page use per_page=1..100. Do not copy an action from a different request. "
            "github_search" ->
                "Use action=search, search_repository or search_code with query. Do not send per_page: " +
                    "github_search does not accept it. max_new_blobs bounds source fetches, not remote pagination. " +
                    "For another page, use the last result's exact continuation_call. "
            else -> ""
        }
        val declared = (schema["properties"] as? JsonObject).orEmpty().keys.sorted().joinToString(", ").take(1000)
        return "Invalid arguments. " + errors.joinToString("; ").take(3000) +
            ". No action was dispatched. " + guidance + "Submit one corrected proposal with a new call ID. " +
            "Declared fields: " + declared
    }

    /** A safe read recipe is constructed from the actual registered schema; no ID is inferred. */
    fun rejection(callId: String, toolName: String, arguments: JsonObject, schema: JsonObject, validationErrors: List<String> = errors(arguments, schema), readRules: Set<dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.ReadRule> = emptySet(), ciRejections: Set<dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.CiRejection> = emptySet(), searchValidationOwner: GitHubSearchValidationEvidence.Owner? = null): AgentToolResult {
        val correction = discoveryCorrection(toolName, arguments, schema)
        val selector = selectorProblem(toolName, arguments)
        val rejected = AgentToolResult(
            callId,
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "gptmobile.arguments.v2")
                    put("operation", toolName)
                    val message = repairMessage(toolName, validationErrors, schema, arguments)
                    put("message", if (correction == null) message else "$message Use correction_call for the bounded read.")
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
            executionReceipt = JsonObject(
                rejectionReceipt(toolName.githubOperationFamily(), arguments, schema, validationErrors, selector) + buildJsonObject {
                    if (toolName.githubOperationFamily() == "github_read") {
                        (arguments["ranges"] as? JsonArray)?.let { ranges ->
                            put("read_range_count", ranges.size)
                            val failures = ranges.filterIsInstance<JsonObject>().flatMap { dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.readWindowFailures(it) }.distinct()
                            if (failures.isNotEmpty()) put("read_window_rejections", JsonArray(failures.map(::JsonPrimitive)))
                        }
                        put("read_invalid_window_count", validationErrors.count { "needs start_line>=1" in it })
                    }
                    if (readRules.isNotEmpty()) {
                        put("semantic_read_rules", JsonArray(readRules.map { JsonPrimitive(it.name) }))
                    }
                    val declaredFields = (schema["properties"] as? JsonObject).orEmpty().keys
                    val unknownFields = if (schema["additionalProperties"] == JsonPrimitive(false)) {
                        arguments.keys.filter { it !in declaredFields && it in dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule.fieldNames }
                    } else {
                        emptyList()
                    }
                    val ci = ciRejections.takeIf { toolName.githubOperationFamily() == "github_ci" }.orEmpty()
                        .filter { it.fields.isNotEmpty() && it.fields.all(declaredFields::contains) }
                    if (ci.isNotEmpty()) put("semantic_ci_rules", JsonArray(ci.map { it.rule.name }.distinct().sorted().map(::JsonPrimitive)))
                    val fields = (unknownFields + readRules.flatMap { it.fields.asList() } + ci.flatMap { it.fields }).distinct().sorted()
                    if (fields.isNotEmpty()) put("validation_field_names", JsonArray(fields.take(12).map(::JsonPrimitive)))
                }
            )
        )
        return searchValidationOwner?.capture(rejected, toolName, arguments, schema) ?: rejected
    }

    /** Typed cause and constraint classes; no argument values enter public diagnostic fields. */
    private fun rejectionReceipt(operation: String, arguments: JsonObject, schema: JsonObject, validationErrors: List<String>, selector: SelectorProblem?): JsonObject {
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val missing = (schema["required"] as? JsonArray).orEmpty().filterIsInstance<JsonPrimitive>()
            .filter { it.isString && it.content !in arguments }.map { it.content }.toMutableSet()
        val invalid = properties.mapNotNull { (field, raw) ->
            val value = arguments[field] ?: return@mapNotNull null
            val constraint = raw as? JsonObject ?: return@mapNotNull null
            field.takeIf { validate(value, constraint, "$", 0).isNotEmpty() }
        }.toMutableSet()
        selector?.let { if (it.missing) missing += it.field else invalid += it.field }
        val classes = linkedSetOf<String>()
        validate(arguments, schema, "$", 0, classes)
        if (operation == "github_context" && arguments["action"] == JsonPrimitive("repositories") && "view_cursor" !in arguments && "query" in properties) {
            when (val query = arguments["query"]) {
                null -> {
                    missing += "query"
                    classes += "MISSING_REQUIRED"
                }
                else -> when {
                    query !is JsonPrimitive || !query.isString -> {
                        invalid += "query"
                        classes += "WRONG_TYPE"
                    }
                    query.content.isBlank() -> {
                        invalid += "query"
                        classes += "EMPTY_STRING"
                    }
                }
            }
        }
        selector?.let {
            val value = arguments[it.field] as? JsonPrimitive
            classes += when {
                it.missing -> "MISSING_REQUIRED"
                value != null && !value.isString && value.content.toBigDecimalOrNull() != null -> "OUT_OF_RANGE"
                else -> "WRONG_TYPE"
            }
        }
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
            put("tool_schema_hash", ToolContractFingerprint.schema(schema))
            put("missing_field_count", missing.size)
            put("invalid_field_count", invalid.size)
            if (classes.isNotEmpty()) put("invalid_field_classes", JsonArray(classes.sorted().map(::JsonPrimitive)))
            put("validation_field_scope", "TOP_LEVEL_AND_KNOWN_SELECTOR")
            if (missing.isNotEmpty()) put("missing_fields", fieldNames(missing))
            if (invalid.isNotEmpty()) put("invalid_fields", fieldNames(invalid))
            if (schema["additionalProperties"] == JsonPrimitive(false)) put("unknown_field_count", arguments.keys.count { it !in properties })
        }
    }

    private fun discoveryCorrection(toolName: String, arguments: JsonObject, schema: JsonObject): JsonObject? {
        val operation = toolName.githubOperationFamily()
        if (operation == "github_search" &&
            arguments.keys.none { it in setOf("view_cursor", "index_cursor", "search_id") } &&
            (integer(arguments["page"]) ?: 1) == 1
        ) {
            val declared = schema["properties"] as? JsonObject
            val properties = declared?.get("max_new_blobs") as? JsonObject
            val requested = integer(arguments["max_new_blobs"])
            val minimum = integer(properties?.get("minimum"))
            val maximum = integer(properties?.get("maximum"))
            val corrected = arguments.toMutableMap()
            if (declared?.containsKey("per_page") == false) corrected.remove("per_page")
            if (requested != null && minimum != null && maximum != null && minimum <= maximum) {
                corrected["max_new_blobs"] = JsonPrimitive(requested.coerceIn(minimum, maximum))
            }
            val proposal = JsonObject(corrected)
            return proposal.takeIf {
                it != arguments &&
                    errors(it, schema).isEmpty() &&
                    dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.errors(operation, it).isEmpty()
            }
        }
        boundedReadCorrection(operation, arguments)?.let { proposal ->
            if (errors(proposal, schema).isEmpty() && dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments.errors(operation, proposal).isEmpty()) return proposal
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

    private fun boundedReadCorrection(operation: String, arguments: JsonObject): JsonObject? {
        if (arguments.keys.any { it in setOf("view_cursor", "index_cursor", "evidence_id", "start_offset") }) return null
        return when (operation to arguments["action"]) {
            "github_ci" to JsonPrimitive("runs") -> {
                val conclusion = arguments["conclusion"]?.takeIf { it == JsonPrimitive("failure") } ?: return null
                if (arguments["status"] !in setOf(null, JsonPrimitive("completed"), conclusion)) return null
                JsonObject(arguments - "conclusion" + ("status" to conclusion))
            }
            "github_ci" to JsonPrimitive("logs") -> {
                val start = if ("start_line" in arguments) integer(arguments["start_line"]) ?: return null else 1
                val end = integer(arguments["end_line"]) ?: return null
                if (start < 1 || end.toLong() - start < 2000) return null
                JsonObject(arguments + ("end_line" to JsonPrimitive(start + 1999)))
            }
            "github_read" to JsonPrimitive("ranges") -> {
                val ranges = arguments["ranges"] as? JsonArray ?: return null
                val corrected = ranges.map { value ->
                    val range = value as? JsonObject ?: return null
                    if (range["end_line"] == JsonPrimitive(-1) && integer(range["max_lines"])?.let { it in 1..2000 } == true) {
                        JsonObject(range - "end_line")
                    } else {
                        range
                    }
                }
                val correctedRanges = JsonArray(corrected)
                if (correctedRanges == ranges) return null
                JsonObject(arguments + ("ranges" to correctedRanges))
            }
            else -> null
        }
    }

    private fun integer(value: JsonElement?): Int? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    private data class SelectorProblem(val field: String, val missing: Boolean, val reason: String, val source: String)

    /** Missing and malformed read selectors need a typed discovery path, not prose parsing. */
    private fun selectorProblem(toolName: String, arguments: JsonObject): SelectorProblem? {
        val operation = toolName.githubOperationFamily()
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

    private fun validate(value: JsonElement, schema: JsonObject, path: String, depth: Int, classes: MutableSet<String>? = null): List<String> {
        if (depth > 24) {
            classes?.add("COMPOSITE_CONSTRAINT")
            return listOf("$path: schema nesting exceeds validation limit")
        }
        val errors = mutableListOf<String>()
        fun invalid(message: String, kind: String) {
            errors += message
            classes?.add(kind)
        }
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
        if (types.isNotEmpty() && types.none(::matches)) {
            classes?.add("WRONG_TYPE")
            return listOf("$path: expected ${types.joinToString(" or ")}")
        }
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (value !in allowed) {
                invalid("$path: unsupported value; allowed: ${allowed.take(20).joinToString().take(500)}", "INVALID_ENUM")
            }
        }
        schema["const"]?.let { if (value != it) invalid("$path: must equal the declared constant", "INVALID_ENUM") }
        for (kind in listOf("allOf", "anyOf", "oneOf")) {
            (schema[kind] as? JsonArray)?.filterIsInstance<JsonObject>()?.let { options ->
                val optionClasses = options.map { linkedSetOf<String>() }
                val failures = options.mapIndexed { index, option -> validate(value, option, path, depth + 1, optionClasses[index]) }
                val matching = failures.count { it.isEmpty() }
                when {
                    kind == "allOf" -> failures.forEachIndexed { index, failure ->
                        failure.take(4).forEach { errors += "$path: allOf[$index]: $it" }
                        if (failure.isNotEmpty()) classes?.addAll(optionClasses[index])
                    }
                    matching == 0 -> {
                        classes?.add("COMPOSITE_CONSTRAINT")
                        val scores = options.map { discriminatorScore(value, it) }
                        val bestScore = scores.maxOrNull() ?: 0
                        val nearest = options.indices.filter { scores[it] == bestScore }
                            .sortedBy { failures[it].size }.take(2)
                        if (nearest.isEmpty()) errors += "$path: $kind has no declared alternatives"
                        nearest.forEach { index ->
                            failures[index].take(4).forEach { errors += "$path: $kind[$index]: $it" }
                            classes?.addAll(optionClasses[index])
                        }
                    }
                    kind == "oneOf" && matching != 1 -> invalid("$path: oneOf matches $matching branches; supply exactly one allowed argument form", "COMPOSITE_CONSTRAINT")
                }
            }
        }
        if (value is JsonObject) {
            val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            (schema["required"] as? JsonArray)?.filterIsInstance<JsonPrimitive>()?.forEach { if (it.content !in value) invalid("$path.${it.content}: required field missing", "MISSING_REQUIRED") }
            value.forEach { (key, child) ->
                val childSchema = properties[key] as? JsonObject ?: schema["additionalProperties"] as? JsonObject
                if (childSchema != null) {
                    errors += validate(child, childSchema, "$path.$key", depth + 1, classes)
                } else if (schema["additionalProperties"] == JsonPrimitive(false) && key !in properties) {
                    invalid("$path.$key: undeclared field", "UNKNOWN_FIELD")
                }
            }
        }
        if (value is JsonArray) {
            (schema["minItems"] as? JsonPrimitive)?.intOrNull?.let { if (value.size < it) invalid("$path: too few items", "OUT_OF_RANGE") }
            (schema["maxItems"] as? JsonPrimitive)?.intOrNull?.let { if (value.size > it) invalid("$path: too many items", "OUT_OF_RANGE") }
            (schema["items"] as? JsonObject)?.let { item -> value.forEachIndexed { index, child -> errors += validate(child, item, "$path[$index]", depth + 1, classes) } }
        }
        if (value is JsonPrimitive && value.isString) {
            (schema["minLength"] as? JsonPrimitive)?.intOrNull?.let { if (value.content.length < it) invalid("$path: too short", if (value.content.isEmpty()) "EMPTY_STRING" else "OUT_OF_RANGE") }
            (schema["maxLength"] as? JsonPrimitive)?.intOrNull?.let { if (value.content.length > it) invalid("$path: too long", "OUT_OF_RANGE") }
            (schema["pattern"] as? JsonPrimitive)?.contentOrNull?.let { pattern ->
                if (runCatching { Regex(pattern).containsMatchIn(value.content) }.getOrDefault(false).not()) invalid("$path: pattern mismatch", "PATTERN_MISMATCH")
            }
        }
        if (value is JsonPrimitive && !value.isString) {
            value.content.toBigDecimalOrNull()?.let { number ->
                (schema["minimum"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.let { if (number < it) invalid("$path: below minimum", "OUT_OF_RANGE") }
                (schema["maximum"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()?.let { if (number > it) invalid("$path: above maximum", "OUT_OF_RANGE") }
            }
        }
        return errors
    }
}
