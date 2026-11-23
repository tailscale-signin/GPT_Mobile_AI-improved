package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.provider.toolRoundSystemPrompt
import java.security.MessageDigest
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

private data class PrivateTextPosition(val kind: PrivateDispatchSurfaceKind, val ordinal: Int)
private data class OrdinaryModalSurface(val position: PrivateTextPosition, val proof: PrivateOrdinaryModalProof)

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
    private val appOwnedSystemTexts: List<AppOwnedSystemText>,
    private val ordinaryModalSurfaces: List<OrdinaryModalSurface>,
    private val decryptValues: () -> List<String>
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PrivateDispatchContext> {
        const val COVERAGE = "KNOWN_PROTECTED_VALUES_IN_TEXT_SURFACES"
        private val textKeys = setOf("content", "text", "system", "prompt", "input", "instructions", "description", "arguments", "tool_result")
        private val systemKeys = setOf("system", "instructions", "systemInstruction", "system_instruction")
        private val markerShape = Regex("\\[PRIVATE_[a-f0-9]{32}_[A-Z][A-Z_]{0,31}_\\d{1,10}]")

        private fun containsValue(text: String, value: String, allowed: (Int, Int) -> Boolean = { _, _ -> false }): Boolean {
            if (value.isEmpty()) return false
            var start = text.indexOf(value, ignoreCase = true)
            while (start >= 0) {
                val end = start + value.length
                val left = !value.first().isLetterOrDigit() || start == 0 || !text[start - 1].isLetterOrDigit()
                val right = !value.last().isLetterOrDigit() || end == text.length || !text[end].isLetterOrDigit()
                if (left && right && !allowed(start, end)) return true
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
        var validatedPublicSuffixes = 0
        var validatedPublicCharacters = 0
        var validatedModalSurfaces = 0
        var retainedModalOccurrences = 0
        val outcome = if (!permissionRevision.endsWith(":privacy-$policyRevision")) {
            "POLICY_REVISION_MISMATCH"
        } else {
            try {
                val values = decryptValues()
                val usedModalSurfaces = hashSetOf<Int>()
                val positionCounts = mutableMapOf<PrivateDispatchSurfaceKind, Int>()
                fun leaked(element: JsonElement, textSurface: Boolean = false, systemSurface: Boolean = false, depth: Int = 0, position: PrivateTextPosition? = null): Boolean {
                    check(depth <= 64) { "PRIVATE_WIRE_DEPTH_EXCEEDED" }
                    return when (element) {
                        is JsonObject -> element.any { (name, value) -> leaked(value, textSurface || name in textKeys, systemSurface, depth + 1) }
                        is JsonArray -> element.any { leaked(it, textSurface, systemSurface, depth + 1) }
                        is JsonPrimitive -> {
                            if (!textSurface || !element.isString) {
                                false
                            } else {
                                inspected++
                                // Only an exact locally registered system value can contain
                                // an app-owned public suffix. Its source prefix remains scanned.
                                val publicSuffix = if (systemSurface) appOwnedSystemTexts.firstOrNull { it.matches(element.content) } else null
                                val sourceText = if (publicSuffix != null) {
                                    validatedPublicSuffixes++
                                    validatedPublicCharacters += publicSuffix.characters
                                    element.content.dropLast(publicSuffix.characters)
                                } else {
                                    element.content
                                }
                                // One recorded occurrence may authorize only its exact original role/ordinal.
                                // Nested JSON and arbitrary content fields never inherit this provenance.
                                val modalIndex = ordinaryModalSurfaces.indexOfFirst { candidate ->
                                    candidate.position == position && candidate.proof.matches(sourceText)
                                }.takeIf { it >= 0 && it !in usedModalSurfaces }
                                val modal = modalIndex?.let {
                                    usedModalSurfaces += it
                                    ordinaryModalSurfaces[it].proof
                                }
                                val sourceOffsets = modal?.let { IntArray(sourceText.length) }
                                // Preserve the existing marker-stripped scan, including matches spanning
                                // a removed marker. Only contiguous original modal offsets may be exempt.
                                var destination = 0
                                var previousEnd = 0
                                val text = if (sourceOffsets == null) {
                                    markerShape.replace(sourceText) { match -> if (match.value in issuedMarkers) "" else match.value }
                                } else {
                                    buildString {
                                        fun copyUntil(end: Int) {
                                            while (previousEnd < end) {
                                                sourceOffsets[destination++] = previousEnd
                                                append(sourceText[previousEnd++])
                                            }
                                        }
                                        markerShape.findAll(sourceText).filter { it.value in issuedMarkers }.forEach { match ->
                                            copyUntil(match.range.first)
                                            previousEnd = match.range.last + 1
                                        }
                                        copyUntil(sourceText.length)
                                    }
                                }
                                val retained = hashSetOf<Pair<Int, Int>>()
                                val knownValueLeaked = values.any { value ->
                                    containsValue(text, value) { start, end ->
                                        if (modal == null || sourceOffsets == null) {
                                            false
                                        } else {
                                            val from = sourceOffsets[start]
                                            val until = sourceOffsets[end - 1] + 1
                                            (until - from == end - start && modal.copiedOccurrence(from, until)).also { allowed ->
                                                if (allowed) retained += from to until
                                            }
                                        }
                                    }
                                }
                                if (retained.isNotEmpty()) {
                                    validatedModalSurfaces++
                                    retainedModalOccurrences += retained.size
                                }
                                knownValueLeaked ||
                                    (text.trimStart().firstOrNull() in setOf('{', '[') && runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let { leaked(it, true, false, depth + 1) } == true)
                            }
                        }
                    }
                }
                fun messageContentLeaks(content: JsonElement, position: PrivateTextPosition, depth: Int, google: Boolean): Boolean = when (content) {
                    is JsonPrimitive -> if (google) leaked(content, depth = depth) else leaked(content, true, depth = depth, position = position)
                    is JsonArray -> content.any { part ->
                        val type = (part as? JsonObject)?.get("type") as? JsonPrimitive
                        val plainText = part is JsonObject &&
                            if (google) {
                                part.keys == setOf("text")
                            } else {
                                type?.isString == true && type.content in setOf("text", "input_text")
                            }
                        if (plainText && part is JsonObject) {
                            part.any { (field, value) ->
                                if (field == "text" && value is JsonPrimitive && value.isString) {
                                    leaked(value, true, depth = depth + 2, position = position)
                                } else {
                                    leaked(value, !google, depth = depth + 2)
                                }
                            }
                        } else {
                            leaked(part, !google, depth = depth + 1)
                        }
                    }
                    else -> leaked(content, !google, depth = depth)
                }
                fun requestLeaks(document: JsonElement): Boolean {
                    if (document !is JsonObject) return leaked(document)
                    return document.any { (name, value) ->
                        if (name in setOf("messages", "contents") && value is JsonArray) {
                            value.any { message ->
                                val role = (message as? JsonObject)?.get("role") as? JsonPrimitive
                                val kind = if (role?.isString == true) {
                                    when {
                                        role.content == "user" -> PrivateDispatchSurfaceKind.USER_MESSAGE
                                        name == "messages" && role.content == "assistant" || name == "contents" && role.content == "model" -> PrivateDispatchSurfaceKind.ASSISTANT_HISTORY
                                        else -> null
                                    }
                                } else {
                                    null
                                }
                                if (message is JsonObject && kind != null) {
                                    val ordinal = positionCounts.getOrDefault(kind, 0)
                                    positionCounts[kind] = ordinal + 1
                                    message.any { (field, content) ->
                                        if (field == if (name == "contents") "parts" else "content") {
                                            messageContentLeaks(content, PrivateTextPosition(kind, ordinal), 3, name == "contents")
                                        } else {
                                            leaked(content, field in textKeys, depth = 3)
                                        }
                                    }
                                } else if (name == "messages" && message is JsonObject && role?.isString == true && role.content in setOf("system", "developer")) {
                                    message.any { (field, content) -> leaked(content, field in textKeys, field == "content", 3) }
                                } else {
                                    leaked(message, depth = 2)
                                }
                            }
                        } else {
                            leaked(value, name in textKeys, name in systemKeys, 1)
                        }
                    }
                }
                when {
                    requestLeaks(Json.parseToJsonElement(payload)) -> "KNOWN_VALUE_REINTRODUCED"
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
            put("known_value_scope", if (retainedModalOccurrences == 0) "TEXT_SURFACES_EXCLUDING_VALIDATED_APP_OWNED_PUBLIC_SUFFIXES" else "TEXT_SURFACES_EXCLUDING_VALIDATED_PUBLIC_SUFFIXES_AND_PROVEN_ORDINARY_MODAL_OCCURRENCES")
            put("app_owned_public_suffix_scope", "EXACT_REGISTERED_SYSTEM_VALUES")
            put("validated_app_owned_public_suffixes", validatedPublicSuffixes)
            put("validated_app_owned_public_characters", validatedPublicCharacters)
            put("ordinary_modal_scope", "DETECTOR_CLASSIFIED_FIRST_PERSON_REQUEST_IN_EXACT_USER_OR_HISTORY_SURFACE")
            put("validated_ordinary_modal_surfaces", validatedModalSurfaces)
            put("retained_ordinary_modal_occurrences", retainedModalOccurrences)
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
        private val appOwnedSystemTexts = mutableListOf<AppOwnedSystemText>()
        private val ordinaryModalSurfaces = mutableListOf<OrdinaryModalSurface>()

        /** Mint provenance only from the fixed app protocol and its actual no-tools transform. */
        fun systemPrompt(protocol: PrivateMarkerProtocol, maskedOriginal: String?): String {
            check(!sealed && appOwnedSystemTexts.isEmpty()) { "PRIVATE_SYSTEM_PROMPT_ALREADY_BOUND" }
            val prompt = protocol.systemPrompt(maskedOriginal)
            val publicSuffix = protocol.systemPrompt(null)
            appOwnedSystemTexts += AppOwnedSystemText.bind(prompt, publicSuffix)
            appOwnedSystemTexts += AppOwnedSystemText.bind(
                requireNotNull(toolRoundSystemPrompt(prompt, false)),
                requireNotNull(toolRoundSystemPrompt(publicSuffix, false))
            )
            return prompt
        }

        fun record(kind: PrivateDispatchSurfaceKind, original: String, protected: PrivateProtectedText) {
            check(!sealed) { "PRIVATE_DISPATCH_BUILDER_SEALED" }
            require(protected.receipt.confirmed && protected.receipt.validFor(original)) { "PRIVATE_SOURCE_RECEIPT_INVALID" }
            protected.ordinaryModalProof?.let { proof ->
                require(proof.validFor(original, protected)) { "PRIVATE_MODAL_SOURCE_INVALID" }
                if (kind in setOf(PrivateDispatchSurfaceKind.USER_MESSAGE, PrivateDispatchSurfaceKind.ASSISTANT_HISTORY)) {
                    ordinaryModalSurfaces += OrdinaryModalSurface(PrivateTextPosition(kind, surfaces.getOrDefault(kind, 0)), proof)
                }
            }
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
            require(ordinaryModalSurfaces.all { it.proof.belongsTo(issuedMarkers) }) { "PRIVATE_MODAL_REQUEST_MISMATCH" }
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
                transformedCharacters, protectedCharacters, protectedRanges, count, summary, issuedMarkers.toSet(), appOwnedSystemTexts.toList(), ordinaryModalSurfaces.toList()
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

/** These local identities are never serialized into evidence or used for non-system text. */
private class AppOwnedSystemText private constructor(private val sha256: ByteArray, val characters: Int) {
    fun matches(text: String): Boolean = MessageDigest.isEqual(sha256, digest(text))

    companion object {
        fun bind(value: String, publicSuffix: String): AppOwnedSystemText {
            require(publicSuffix.isNotEmpty() && value.endsWith(publicSuffix))
            return AppOwnedSystemText(digest(value), publicSuffix.length)
        }

        private fun digest(text: String): ByteArray {
            val bytes = text.encodeToByteArray()
            return try {
                MessageDigest.getInstance("SHA-256").digest(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }
}

internal class PrivateDispatchRejected(val receipt: JsonObject) : IllegalStateException("PRIVATE_FINAL_REQUEST_REJECTED")
