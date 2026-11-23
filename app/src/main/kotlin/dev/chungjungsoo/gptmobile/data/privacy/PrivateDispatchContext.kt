package dev.chungjungsoo.gptmobile.data.privacy

import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class PrivateDispatchSurfaceKind { USER_MESSAGE, ASSISTANT_HISTORY, SYSTEM_PROMPT, TOOL_RESULT }

/** Only counts, revisions and a request-keyed fingerprint leave this request's local lifetime. */
internal class PrivateDispatchContext private constructor(
    private val policyRevision: Long,
    private val categories: Set<PrivacyCategory>,
    private val detectorRevision: String,
    private val surfaces: Map<PrivateDispatchSurfaceKind, Int>,
    private val originalCharacters: Long,
    private val transformedCharacters: Long,
    private val protectedCharacters: Long,
    private val protectedRanges: Int,
    private val knownValueCount: Int,
    private val maskSummary: PrivateMaskSummary?,
    private val issuedMarkers: Set<String>,
    private val decryptValues: () -> List<String>
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PrivateDispatchContext> {
        const val COVERAGE = "KNOWN_PROTECTED_VALUES_IN_TEXT_SURFACES"
        private val textKeys = setOf("content", "text", "system", "prompt", "input", "instructions", "description", "arguments", "tool_result")
        private val markerShape = Regex("\\[PRIVATE_[a-f0-9]{32}_[A-Z][A-Z_]{0,31}_\\d{1,10}]")

        private fun containsValue(text: String, value: String): Boolean {
            if (value.isEmpty()) return false
            var start = text.indexOf(value, ignoreCase = true)
            while (start >= 0) {
                val end = start + value.length
                val left = !value.first().isLetterOrDigit() || start == 0 || !text[start - 1].isLetterOrDigit()
                val right = !value.last().isLetterOrDigit() || end == text.length || !text[end].isLetterOrDigit()
                if (left && right) return true
                start = text.indexOf(value, start + 1, ignoreCase = true)
            }
            return false
        }
    }

    private val fingerprintKey = KeyGenerator.getInstance("HmacSHA256").apply { init(256) }.generateKey()
    private val proofId = java.util.UUID.randomUUID().toString()
    private val streamEvidence = AtomicReference<JsonObject?>()

    fun stream(observation: PrivateStreamObservation) {
        streamEvidence.set(observation.evidence())
    }

    fun streamSnapshot(): JsonObject? = streamEvidence.get()?.let { JsonObject(it + ("private_request_id" to JsonPrimitive(proofId))) }

    /** Called on the final serialized body before an inference attempt is reserved or transmitted. */
    fun validate(payload: String, permissionRevision: String): JsonObject {
        val started = System.nanoTime()
        var inspected = 0
        val outcome = if (!permissionRevision.endsWith(":privacy-$policyRevision")) {
            "POLICY_REVISION_MISMATCH"
        } else {
            try {
                val values = decryptValues()
                fun leaked(element: JsonElement, textSurface: Boolean = false, depth: Int = 0): Boolean {
                    check(depth <= 64) { "PRIVATE_WIRE_DEPTH_EXCEEDED" }
                    return when (element) {
                        is JsonObject -> element.any { (name, value) -> leaked(value, textSurface || name in textKeys, depth + 1) }
                        is JsonArray -> element.any { leaked(it, textSurface, depth + 1) }
                        is JsonPrimitive -> {
                            if (!textSurface || !element.isString) {
                                false
                            } else {
                                inspected++
                                // Generated markers contain category names and ordinals, not source values.
                                val text = markerShape.replace(element.content) { match -> if (match.value in issuedMarkers) "" else match.value }
                                values.any { containsValue(text, it) } ||
                                    (text.trimStart().firstOrNull() in setOf('{', '[') && runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let { leaked(it, true, depth + 1) } == true)
                            }
                        }
                    }
                }
                when {
                    leaked(Json.parseToJsonElement(payload)) -> "KNOWN_VALUE_REINTRODUCED"
                    inspected == 0 -> "NO_SUPPORTED_TEXT_SURFACES"
                    else -> "KNOWN_VALUES_ABSENT"
                }
            } catch (_: Exception) {
                "WIRE_VALIDATION_UNAVAILABLE"
            }
        }
        val bytes = payload.encodeToByteArray()
        val fingerprint = Mac.getInstance("HmacSHA256").apply { init(fingerprintKey) }.doFinal(bytes).joinToString("") { "%02x".format(it) }
        val receipt = buildJsonObject {
            put("version", 1)
            put("private_request_id", proofId)
            put("policy_revision", policyRevision)
            put("detector_revision", detectorRevision)
            put("coverage", COVERAGE)
            put("outcome", outcome)
            put("unrecognized_sensitive_values", "NOT_ASSESSED")
            put("provider_retention", "NOT_ASSESSED")
            put("payload_hmac", fingerprint)
            put("fingerprint_scope", "REQUEST_LOCAL_KEY")
            put("wire_bytes", bytes.size)
            put("validation_elapsed_us", (System.nanoTime() - started) / 1_000)
            put("text_surfaces_checked", inspected)
            put("known_private_values", knownValueCount)
            put("protected_ranges", protectedRanges)
            put("original_characters", originalCharacters)
            put("transformed_characters", transformedCharacters)
            put("protected_characters", protectedCharacters)
            put("configured_categories", JsonArray(categories.sortedBy { it.name }.map { JsonPrimitive(it.name) }))
            put("surface_counts", buildJsonObject { surfaces.forEach { (kind, count) -> put(kind.name, count) } })
            maskSummary?.let { summary ->
                put("location_policy", summary.locationPolicy)
                put("generated_markers", summary.generatedMarkerCount)
                put("matches_by_category", buildJsonObject { summary.matchesByCategory.forEach { (category, count) -> put(category, count) } })
            }
        }
        bytes.fill(0)
        if (outcome != "KNOWN_VALUES_ABSENT") throw PrivateDispatchRejected(receipt)
        return receipt
    }

    class Builder(
        private val policyRevision: Long,
        private val categories: Set<PrivacyCategory>,
        private val detectorRevision: String = "privacy-detector-v2"
    ) {
        private val values = linkedSetOf<String>()
        private val surfaces = linkedMapOf<PrivateDispatchSurfaceKind, Int>()
        private var originalCharacters = 0L
        private var transformedCharacters = 0L
        private var protectedCharacters = 0L
        private var protectedRanges = 0
        private var sealed = false

        fun record(kind: PrivateDispatchSurfaceKind, original: String, protected: PrivateProtectedText) {
            check(!sealed) { "PRIVATE_DISPATCH_BUILDER_SEALED" }
            require(protected.receipt.confirmed && protected.receipt.validFor(original)) { "PRIVATE_SOURCE_RECEIPT_INVALID" }
            surfaces[kind] = surfaces.getOrDefault(kind, 0) + 1
            originalCharacters += original.length
            transformedCharacters += protected.text.length
            protected.receipt.ranges.forEach { range ->
                values += original.substring(range.start, range.endExclusive)
                protectedCharacters += range.endExclusive - range.start
                protectedRanges++
            }
        }

        fun seal(summary: PrivateMaskSummary? = null, issuedMarkers: Set<String> = emptySet()): PrivateDispatchContext {
            check(!sealed) { "PRIVATE_DISPATCH_BUILDER_SEALED" }
            sealed = true
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val bytes = Json.encodeToString(values.toList()).encodeToByteArray()
            val encrypted = try {
                cipher.doFinal(bytes)
            } finally {
                bytes.fill(0)
            }
            val iv = cipher.iv
            val count = values.size
            values.clear()
            return PrivateDispatchContext(
                policyRevision, categories.toSet(), summary?.detectorRevision ?: detectorRevision, surfaces.toMap(), originalCharacters,
                transformedCharacters, protectedCharacters, protectedRanges, count, summary, issuedMarkers.toSet()
            ) {
                val reader = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
                val plaintext = reader.doFinal(encrypted)
                try {
                    Json.decodeFromString<List<String>>(plaintext.decodeToString())
                } finally {
                    plaintext.fill(0)
                }
            }
        }
    }
}

internal class PrivateDispatchRejected(val receipt: JsonObject) : IllegalStateException("PRIVATE_FINAL_REQUEST_REJECTED")
