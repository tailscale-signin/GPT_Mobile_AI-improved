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
        val credentialSafe = (credentialsOnly.redact(JsonPrimitive(value)) as JsonPrimitive).content
        if (mode == DiagnosticContentMode.FULL_FORENSIC) return credentialSafe
        // Old retained logs can predate recorder redaction. Reapply it to the entire
        // frozen line before saving the resumable snapshot, not only the shared zip.
        return scrubText(redactLogContent(redactLogMessage(credentialSafe)))
    }

    private fun clean(value: JsonElement, surfaceCounts: Boolean = false): JsonElement = when (value) {
        is JsonObject -> {
            val fields = value.keys.map(::normalize).toSet()
            val configuredConnection = "apiurl" in fields && "compatibletype" in fields || "endpointurl" in fields && "connectionuid" in fields
            val researchJob = "jobid" in fields && "stage" in fields
            JsonObject(
                value.mapValues { (key, child) ->
                    val normalized = normalize(key)
                    when {
                        child == JsonNull -> child
                        surfaceCounts && normalized in privateDispatchSurfaces && child is JsonPrimitive && !child.isString && child.content.toLongOrNull()?.let { it >= 0 } == true -> child
                        configuredConnection && normalized in setOf("name", "alias") -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                        researchJob && normalized == "reason" -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                        normalized in opaquePrivateFields || normalized in configuredTopologyFields -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                        privateTextField.matches(normalized) && (child is JsonArray || child is JsonPrimitive && child.isString) -> JsonPrimitive(PRIVATE_CONTENT_OMITTED)
                        normalized == "line" && child is JsonPrimitive && child.isString -> JsonPrimitive(logLine(child.content))
                        else -> clean(child, surfaceCounts = normalized == "surfacecounts")
                    }
                }
            )
        }
        is JsonArray -> JsonArray(value.map(::clean))
        is JsonPrimitive -> if (!value.isString) {
            value
        } else {
            val text = value.content
            val embedded = if (text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
                runCatching { Json.parseToJsonElement(text) }.getOrNull()
            } else {
                null
            }
            if (embedded is JsonObject || embedded is JsonArray) JsonPrimitive(clean(embedded).toString()) else JsonPrimitive(scrubText(text))
        }
    }

    /** A log prefix can contain assignments before a complete JSON evidence object. */
    private fun redactLogContent(value: String): String = buildString {
        var offset = 0
        while (offset < value.length) {
            val start = value.indexOfAny(charArrayOf('{', '['), offset)
            if (start < 0) {
                append(scrubLogAssignments(value.substring(offset)))
                break
            }
            val prefix = value.substring(offset, start)
            val privatePayload = privateLogAssignment.findAll(prefix).lastOrNull()?.let { it.range.last == prefix.lastIndex && it.groupValues[2].isBlank() } == true
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
        private val configuredTopologyFields = setOf(
            "apiurl", "batchapiurl", "baseurl", "apibaseurl", "gatewaybaseurl", "endpoint", "endpointurl", "serverurl", "gatewayurl", "connectionurl",
            "host", "hostname", "hostaddress", "serverhost", "serverhostname", "localhostname", "remotehost", "bindhost", "bindaddress",
            "port", "serverport", "localport", "ipaddress", "localaddress", "localip", "gatewayaddress", "macaddress",
            "devicename", "devicelabel", "devicehostname", "deviceaddress", "deviceip", "tailscalehostname", "tailscaleip"
        )
        private val opaquePrivateFields = setOf(
            "arguments", "toolarguments", "resolvedarguments", "requestbody", "responsebody",
            "privateterms", "customprivateterms", "replacementmap", "replacements", "decryptedcontent",
            "recalledfacts", "recalledmemory", "attachments", "draftattachments", "labels",
            "query", "queries", "prompt", "userprompt", "systemprompt", "thoughts", "thinking",
            "localport", "localip", "gatewayaddress", "serialnumber", "androidid", "deviceid",
            "profilename", "connectionname", "profilelabel", "connectionlabel",
            "error", "failuremessage", "failurereason", "terminalreason", "lasterror", "errormessage", "errorbody"
        )
        private val privateTextField = Regex(
            "(?:.*(?:content|text|prompt|query|thoughts|thinking|reasoningtext|reasoningcontent|reasoningdetails|snippet|quote|errormessage|errorbody|failuremessage|failurereason|terminalreason|lasterror)|" +
                "title|description|summary|rationale|explanation|answer|output|input|body|message|error|terminalerror|" +
                "composerdraft|draft|result|document|payload|request|response|reasoning|email|emailaddress|phone|phonenumber|address|recipient|sender)"
        )

        // Structured objects take the same recursive policy as archive records.
        // Legacy labelled lines stop at the next diagnostic field or separator.
        private val httpEndpointLabel = Regex("(?i)(\\b(?:REQUEST|FROM|URL|ENDPOINT|LOCATION)[ \\t]*:[ \\t]*)https?://[^\\s\"'<>]+")
        private val logFieldBoundary = Regex("[ \\t]*·|[\\r\\n]|[ \\t]+[A-Za-z][A-Za-z0-9_]*[ \\t]*[:=]")
        private val privateLogAssignment = Regex(
            "(?i)((?<![\\w])(?:" +
                (configuredTopologyFields + setOf("profilename", "connectionname", "profilelabel", "connectionlabel", "failuremessage", "failurereason", "terminalreason", "lasterror", "errormessage", "errorbody", "error")).joinToString("|") { field ->
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
