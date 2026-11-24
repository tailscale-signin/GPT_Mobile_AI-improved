package dev.chungjungsoo.gptmobile.data.diagnostics

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Content consent is separate from history depth, debug recording and conversation privacy. */
enum class DiagnosticContentMode(val captureIdentity: String, val explanation: String) {
    REDACTED(
        "redacted-content-v1",
        "Private text, model thoughts, private terms and local network details are omitted. Credentials are always redacted. Record identifiers, protection ranges, timings and structural evidence remain available."
    ),
    FULL_FORENSIC(
        "full-forensic-v1",
        "Explicit full forensic export: original private conversation text, model thoughts and configured network details may be included. Credentials are still redacted."
    )
}

/** Apply before chunking, payload deduplication, checkpointing and final section hashes. */
internal class DiagnosticContentRedactor(
    private val mode: DiagnosticContentMode = DiagnosticContentMode.REDACTED,
    credentials: List<String> = emptyList()
) {
    private val credentialsOnly = ChatDebugRedactor(credentials)

    fun redact(value: JsonElement): JsonElement {
        val credentialSafe = credentialsOnly.redact(value)
        return if (mode == DiagnosticContentMode.FULL_FORENSIC) credentialSafe else clean(credentialSafe)
    }

    fun logLine(value: String): String {
        // A physical continuation retains correlation, not a trusted field schema.
        // Recognize its producer prefix before a credential may replace the ID.
        val continuation = if (mode == DiagnosticContentMode.REDACTED) continuationLogPrefix.find(value) else null
        val exportValue = if (continuation != null) {
            val payload = value.substring(continuation.value.length)
            // Ktor's fixed method vocabulary contains no free-form response text.
            continuation.value + if (safeHttpMethodContinuation.matches(payload)) payload else PRIVATE_CONTENT_OMITTED
        } else {
            value
        }
        val credentialSafe = (credentialsOnly.redact(JsonPrimitive(exportValue)) as JsonPrimitive).content
        if (mode == DiagnosticContentMode.FULL_FORENSIC || continuation != null) return credentialSafe
        // Old retained logs can predate recorder redaction. Reapply it to the entire
        // frozen line before saving the resumable snapshot, not only the shared zip.
        return scrubText(redactLogContent(redactLogMessage(credentialSafe)))
    }

    private fun clean(value: JsonElement, surfaceCounts: Boolean = false): JsonElement = when (value) {
        is JsonObject -> {
            val fields = value.keys.map(::normalize).toSet()
            val configuredConnection = "apiurl" in fields && "compatibletype" in fields || "endpointurl" in fields && "connectionuid" in fields
            val researchJob = "jobid" in fields && "stage" in fields
            val toolEventFields = (value["fields"] as? JsonObject)?.takeIf { value["table"] == JsonPrimitive("tool_events") && "result_type" in it }
            val observationPointer = if ("receiptid" in fields && "pointer" in fields) {
                (value.entries.firstOrNull { normalize(it.key) == "pointer" }?.value as? JsonPrimitive)?.takeIf { it.isString }?.content
            } else {
                null
            }
            val sourceExcerpt = fields.containsAll(setOf("value", "revision", "startline", "endline"))
            value.mapFieldsIfChanged { key, child ->
                val normalized = normalize(key)
                when {
                    child == JsonNull -> child
                    key == "fields" && toolEventFields != null -> cleanToolEventFields(toolEventFields)
                    surfaceCounts && normalized in privateDispatchSurfaces && child is JsonPrimitive && !child.isString && child.content.toLongOrNull()?.let { it >= 0 } == true -> child
                    normalized in retainedPrivateTextFields -> omitRetainedText(child)
                    sourceExcerpt && normalized in setOf("value", "resource") -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                    observationPointer != null && normalized in setOf("value", "observed", "valuejson") -> redactObservation(child, observationPointer, encoded = normalized == "valuejson")
                    normalized in setOf("facts", "retainedfacts") && child is JsonObject -> JsonObject(child.mapValues { (pointer, fact) -> redactObservation(fact, pointer) })
                    configuredConnection && normalized in setOf("name", "alias") -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                    researchJob && normalized == "reason" -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                    normalized in opaquePrivateFields || normalized in configuredTopologyFields -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                    normalized in privateRepositoryPathFields -> cleanRepositoryPaths(child)
                    privateTextField.matches(normalized) && (child is JsonArray || child is JsonPrimitive && child.isString) -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                    normalized == "line" && child is JsonPrimitive && child.isString -> JsonPrimitive(logLine(child.content))
                    else -> clean(child, surfaceCounts = normalized == "surfacecounts")
                }
            }
        }
        is JsonArray -> value.mapChildrenIfChanged(::clean)
        is JsonPrimitive -> if (!value.isString) {
            value
        } else {
            val text = value.content
            val embedded = if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
                runCatching { Json.parseToJsonElement(text) }.getOrNull()
            } else {
                null
            }
            val safe = if (embedded is JsonObject || embedded is JsonArray) clean(embedded).toString() else scrubText(text)
            if (safe == text) value else JsonPrimitive(safe)
        }
    }

    /** Already-safe branches keep their immutable nodes instead of duplicating the entire receipt. */
    private inline fun JsonObject.mapFieldsIfChanged(transform: (String, JsonElement) -> JsonElement): JsonObject {
        var changed: MutableMap<String, JsonElement>? = null
        for ((key, child) in this) {
            val safe = transform(key, child)
            if (safe !== child) {
                if (changed == null) changed = LinkedHashMap(this)
                changed[key] = safe
            }
        }
        return changed?.let(::JsonObject) ?: this
    }

    private inline fun JsonArray.mapChildrenIfChanged(transform: (JsonElement) -> JsonElement): JsonArray {
        var changed: MutableList<JsonElement>? = null
        for ((index, child) in withIndex()) {
            val safe = transform(child)
            if (safe !== child) {
                if (changed == null) changed = toMutableList()
                changed[index] = safe
            }
        }
        return changed?.let(::JsonArray) ?: this
    }

    /** Retained task schemas contain both raw strings and compiled, identified criteria. */
    private fun omitRetainedText(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::omitRetainedText))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(PRIVATE_CONTENT_OMITTED) else value
        else -> clean(value)
    }

    /** Citation values are source content even when their JSON field is only `value` or `observed`. */
    private fun redactObservation(value: JsonElement, pointer: String, encoded: Boolean = false): JsonElement {
        if (encoded) {
            val document = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            return JsonPrimitive((document?.let { redactObservation(it, pointer) } ?: JsonPrimitive(PRIVATE_CONTENT_OMITTED)).toString())
        }
        if (value == JsonNull) return value
        val primitive = value as? JsonPrimitive ?: return JsonPrimitive(PRIVATE_CONTENT_OMITTED)
        val field = normalize(pointer.substringAfterLast('/'))
        val numeric = field in observationNumbers && primitive.content.toLongOrNull()?.let { it >= 0 } == true
        val boolean = field in observationBooleans && !primitive.isString && primitive.content in setOf("true", "false")
        val hash = field in observationHashes && primitive.isString && observationHash.matches(primitive.content)
        val status = field in setOf("status", "state", "conclusion", "outcome") && primitive.isString && primitive.content.uppercase(Locale.ROOT) in observationStatuses
        val code = field in setOf("errorcode", "reasoncode") && primitive.isString && observationCode.matches(primitive.content)
        return if (numeric || boolean || hash || status || code) primitive else JsonPrimitive(PRIVATE_CONTENT_OMITTED)
    }

    private fun cleanRepositoryPaths(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::cleanRepositoryPaths))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(PRIVATE_CONTENT_OMITTED) else value
        else -> clean(value)
    }

    /** A projected JSON tool response is still original content, not a diagnostic schema. */
    private fun cleanToolEventFields(fields: JsonObject): JsonObject = clean(
        JsonObject(
            fields.mapValues { (key, value) ->
                when (key) {
                    "connection_name_snapshot" -> if (value == JsonNull) value else JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                    "result" -> when {
                        value == JsonNull -> value
                        fields["result_type"] == JsonPrimitive("CHECKPOINT_V1") -> cleanToolCheckpoint(value)
                        else -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                    }
                    else -> value
                }
            }
        )
    ) as JsonObject

    private fun cleanToolCheckpoint(value: JsonElement): JsonElement {
        val saved = value as? JsonObject ?: (value as? JsonPrimitive)?.takeIf { it.isString }?.let {
            runCatching { Json.parseToJsonElement(it.content) as? JsonObject }.getOrNull()
        } ?: return JsonPrimitive(PRIVATE_CONTENT_OMITTED)
        if (saved["payload_type"] !in toolPayloadTypes || "payload" !in saved || "display" !in saved) return JsonPrimitive(PRIVATE_CONTENT_OMITTED)
        return JsonObject(
            buildMap {
                put("payload_type", saved.getValue("payload_type"))
                put("payload", JsonPrimitive(PRIVATE_CONTENT_OMITTED))
                put("display", JsonPrimitive(PRIVATE_CONTENT_OMITTED))
                (saved["execution"] as? JsonObject)?.let { put("execution", clean(it)) }
                (saved["personal_data"] as? JsonArray)?.let { labels -> put("personal_data", JsonArray(labels.map(::cleanToolProvenance))) }
            }
        )
    }

    private fun cleanToolProvenance(value: JsonElement): JsonElement {
        val label = value as? JsonObject ?: return JsonPrimitive(PRIVATE_CONTENT_OMITTED)
        return JsonObject(
            label.filterKeys { normalize(it) in toolProvenanceFields }.mapValues { (key, child) ->
                val primitive = child as? JsonPrimitive
                when {
                    child == JsonNull -> child
                    normalize(key) in setOf("sourcetoolid", "sourceprofileuid", "sourceconnectionuid") && primitive?.isString == true && diagnosticOwnerId.matches(primitive.content) -> child
                    normalize(key) in setOf("version", "retentionchatid", "rangestart", "rangeend") && primitive?.isString == false && primitive.content.toLongOrNull()?.let { it >= 0 } == true -> child
                    normalize(key) == "localonly" && child in setOf(JsonPrimitive(true), JsonPrimitive(false)) -> child
                    normalize(key) == "categories" && child is JsonArray -> JsonArray(child.map { if (it in personalDataCategories) it else JsonPrimitive(PRIVATE_CONTENT_OMITTED) })
                    normalize(key) == "sourcerecipients" && child is JsonArray -> JsonArray(child.map { JsonPrimitive(PRIVATE_CONTENT_OMITTED) })
                    else -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                }
            }
        )
    }

    /** A log prefix can contain assignments before a complete JSON evidence object. */
    private fun redactLogContent(value: String): String = buildString {
        var offset = 0
        while (offset < value.length) {
            val start = value.indexOfAny(charArrayOf('{', '['), offset)
            val prefix = value.substring(offset, if (start < 0) value.length else start)
            // Provider and memory producers append detail last. Its arbitrary
            // text cannot introduce trusted fields, separators or JSON evidence.
            val terminalDetail = terminalLogDetail.find(prefix)
            if (terminalDetail != null) {
                append(scrubLogAssignments(prefix.substring(0, terminalDetail.range.first)))
                append(terminalDetail.value)
                append(PRIVATE_CONTENT_OMITTED)
                break
            }
            if (start < 0) {
                append(scrubLogAssignments(prefix))
                break
            }
            val privatePayload = privateLogAssignment.findAll(prefix).lastOrNull()?.let { it.range.last == prefix.lastIndex } == true
            append(scrubLogAssignments(prefix))
            val redactedLabel = redactedLogLabels.firstOrNull { value.startsWith(it, start) }
            if (redactedLabel != null) {
                if (!privatePayload) append(redactedLabel)
                val labelEnd = start + redactedLabel.length
                offset = if (privatePayload) privateLogFieldEnd(value, labelEnd) else labelEnd
                continue
            }
            val end = diagnosticJsonEnd(value, start)
            val element = end?.let { runCatching { Json.parseToJsonElement(value.substring(start, it)) }.getOrNull() }
            if (element == null) {
                // A truncated/oversized JSON payload has no trustworthy field
                // boundary. Retain its correlation prefix and omit the remainder.
                append(UNPARSED_PAYLOAD_OMITTED)
                break
            }
            if (!privatePayload) append(clean(element).toString())
            offset = if (privatePayload) privateLogFieldEnd(value, requireNotNull(end)) else requireNotNull(end)
        }
    }

    private fun privateLogFieldEnd(value: String, start: Int): Int = logFieldBoundary.find(value, start)?.range?.first ?: value.length

    private fun scrubLogAssignments(value: String): String = scrubText(
        privateLogAssignment.replace(httpEndpointLabel.replace(value) { match -> "${match.groupValues[1]}$PRIVATE_CONTENT_OMITTED" }) { match -> "${match.groupValues[1]}$PRIVATE_CONTENT_OMITTED" }
    )

    private fun scrubText(value: String): String = value
        .replace(localUrl, "[local-endpoint omitted]")
        .replace(privateAddress, "[local-address omitted]")
        .replace(privateIpv6, "[local-address omitted]")
        .replace(redactedAddressPort, "[local-endpoint omitted]")
        .replace(privatePath, "[local-path omitted]")
        .replace(urlQuery, "$1?[query omitted]")

    companion object {
        const val PRIVATE_CONTENT_OMITTED = "[private content omitted: redacted export]"
        private const val UNPARSED_PAYLOAD_OMITTED = "[unparsed diagnostic payload omitted]"
        private val redactedLogLabels = listOf(
            PRIVATE_CONTENT_OMITTED, UNPARSED_PAYLOAD_OMITTED, "[redacted]", "[private payload redacted]",
            "[private-path]", "[private-address]", "[local-endpoint omitted]", "[local-address omitted]",
            "[local-path omitted]", "[query omitted]", "[redacted chat collection]", "[oversized diagnostic message omitted]"
        )
        private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace("_", "").replace("-", "")
        private val privateDispatchSurfaces = setOf("usermessage", "assistanthistory", "systemprompt", "toolresult")
        private val retainedPrivateTextFields = setOf("originalrequest", "requestedactions", "acceptancecriteria", "task", "requirement", "subject", "claim")
        private val observationNumbers = setOf(
            "id", "number", "runnumber", "runattempt", "attempt", "count", "totalcount", "additions", "deletions", "changedfiles", "commits", "comments", "reviewcomments",
            "startline", "endline", "totallines", "bytes", "size", "sizeinbytes", "durationms", "elapsedms", "createdatms", "updatedatms", "observedatms",
            "tests", "failures", "errors", "skipped", "passed", "failed"
        )
        private val observationBooleans = setOf("success", "merged", "draft", "truncated", "sourcetruncated", "rangecomplete", "complete", "verified", "exists", "deleted", "trusted", "verificationeligible")
        private val observationHashes = setOf("sha", "headsha", "basesha", "commitsha", "expectedblobsha", "sourceheadsha", "resultsha256", "digest", "sha256", "requestdigest")
        private val observationHash = Regex("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})")
        private val observationCode = Regex("[A-Z][A-Z0-9_]{0,95}")
        private val observationStatuses = setOf(
            "OPEN", "CLOSED", "QUEUED", "PENDING", "WAITING", "REQUESTED", "IN_PROGRESS", "COMPLETED", "SUCCESS", "FAILURE", "FAILED", "NEUTRAL", "CANCELLED", "SKIPPED", "TIMED_OUT", "ACTION_REQUIRED", "STALE",
            "VERIFIED", "HISTORICAL", "CONTRADICTED", "NOT_MEASURED", "INFERRED", "PARTIAL", "COMPLETE", "UNAVAILABLE", "BLOCKED", "PASSED"
        )
        private val toolPayloadTypes = setOf("TEXT", "JSON", "RESOURCE_LINKS").map(::JsonPrimitive).toSet()
        private val diagnosticOwnerId = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,191}")
        private val toolProvenanceFields = setOf("version", "sourcetoolid", "sourceprofileuid", "sourceconnectionuid", "sourceref", "categories", "sourcerecipients", "retentionchatid", "rangestart", "rangeend", "localonly")
        private val personalDataCategories = setOf("MEMORY", "HEALTH", "COARSE_LOCATION", "PRECISE_LOCATION", "PRIVATE_CONTENT", "UNCLASSIFIED").map(::JsonPrimitive).toSet()

        // Repository checkpoints contain relative paths that local filesystem
        // matching cannot identify. Keep schema objects and numeric evidence.
        private val privateRepositoryPathFields = setOf("path", "filename", "previousfilename", "pathprefix", "selectedpaths", "pendingsourcepaths")
        private val configuredTopologyFields = setOf(
            "apiurl", "batchapiurl", "baseurl", "apibaseurl", "gatewaybaseurl", "endpoint", "endpointurl", "serverurl", "gatewayurl", "connectionurl",
            "host", "hostname", "hostaddress", "serverhost", "serverhostname", "localhostname", "remotehost", "bindhost", "bindaddress",
            "port", "serverport", "localport", "ipaddress", "localaddress", "localip", "gatewayaddress", "macaddress",
            "devicename", "devicelabel", "devicehostname", "deviceaddress", "deviceip", "tailscalehostname", "tailscaleip"
        )
        private val opaquePrivateFields = setOf(
            "arguments", "toolarguments", "resolvedarguments", "canonicalarguments", "requestbody", "responsebody",
            "privateterms", "customprivateterms", "replacementmap", "replacements", "decryptedcontent",
            "recalledfacts", "recalledmemory", "attachments", "draftattachments", "labels",
            "query", "queries", "prompt", "userprompt", "systemprompt", "thoughts", "thinking",
            "localport", "localip", "gatewayaddress", "serialnumber", "androidid", "deviceid",
            "profilename", "connectionname", "profilelabel", "connectionlabel",
            "error", "failuremessage", "failurereason", "terminalreason", "lasterror", "errormessage", "errorbody"
        )

        // Provider and memory failures use `detail` for arbitrary exception text.
        // Structured `details` objects still recurse so their safe evidence survives.
        private val privateTextField = Regex(
            "(?:.*(?:content|text|prompt|query|thoughts|thinking|reasoningtext|reasoningcontent|reasoningdetails|snippet|quote|errormessage|errorbody|failuremessage|failurereason|terminalreason|lasterror)|" +
                "title|description|detail|summary|rationale|explanation|answer|output|input|body|message|error|terminalerror|" +
                "composerdraft|draft|result|document|payload|request|response|reasoning|email|emailaddress|phone|phonenumber|address|recipient|sender)"
        )

        // Structured objects take the same recursive policy as archive records.
        // Legacy labelled lines stop at the next diagnostic field or separator.
        private val httpEndpointLabel = Regex("(?i)(\\b(?:REQUEST|FROM|URL|ENDPOINT|LOCATION)[ \\t]*:[ \\t]*)https?://[^\\s\"'<>]+")
        private val terminalLogDetail = Regex("(?i)(?<![\\w])detail[ \\t]*[:=][ \\t]*")
        private val continuationLogPrefix = Regex("^parentEventId=(?:[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}|\\[redacted\\]) · sequence=[1-9][0-9]{0,8} · ")
        private val safeHttpMethodContinuation = Regex("METHOD: (?:GET|HEAD|POST|PUT|DELETE|CONNECT|OPTIONS|TRACE|PATCH)")
        private val logFieldBoundary = Regex("[ \\t]*·|[\\r\\n]|[ \\t]+[A-Za-z][A-Za-z0-9_]*[ \\t]*[:=]")
        private val privateLogAssignment = Regex(
            "(?i)((?<![\\w])(?:" +
                (configuredTopologyFields + setOf("profilename", "connectionname", "profilelabel", "connectionlabel", "failuremessage", "failurereason", "terminalreason", "lasterror", "errormessage", "errorbody", "error", "detail")).joinToString("|") { field ->
                    field.toList().joinToString("[_-]?")
                } +
                ")[ \\t]*[:=][ \\t]*)([^·\\r\\n]*?)(?=[ \\t]+[A-Za-z][A-Za-z0-9_]*[ \\t]*[:=]|[ \\t]*·|[\\r\\n]|$)"
        )
        private val localHost = "(?:localhost|127(?:\\.\\d{1,3}){3}|0\\.0\\.0\\.0|10(?:\\.\\d{1,3}){3}|192\\.168(?:\\.\\d{1,3}){2}|172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2}|100\\.(?:6[4-9]|[7-9]\\d|1[01]\\d|12[0-7])(?:\\.\\d{1,3}){2}|[a-z0-9_.-]+\\.(?:local|internal)|\\[(?:::1|f[cd][0-9a-f:]+|fe[89ab][0-9a-f:]+)(?:%[^]\\s]+)?])"
        private val localUrl = Regex("(?i)https?://(?:$localHost|[a-z][a-z0-9_-]*)(?![a-z0-9_.-])(?::\\d+)?(?:/[^\\s\"'<>]*)?")
        private val privateAddress = Regex("(?i)(?<![a-z0-9.])$localHost(?::\\d+)?(?![a-z0-9.])")
        private val privateIpv6 = Regex("(?i)(?<![0-9a-f:])(?:f[cd][0-9a-f]{2}|fe[89ab][0-9a-f]):[0-9a-f:]+(?:%[a-z0-9]+)?")
        private val redactedAddressPort = Regex("\\[private-address]:\\d+(?:/[^\\s\"'<>]*)?")
        private val privatePath = Regex("(?:/(?:data|storage|sdcard|home)/|(?<![A-Za-z0-9])[A-Za-z]:[\\\\/])[^\\s\"',;]+")
        private val urlQuery = Regex("(https?://[^\\s?\"']+)\\?[^\\s\"']+")
    }
}
