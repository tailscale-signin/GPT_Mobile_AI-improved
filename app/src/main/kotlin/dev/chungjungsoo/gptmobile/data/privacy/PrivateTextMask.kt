package dev.chungjungsoo.gptmobile.data.privacy

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Request-scoped markers cannot accidentally restore lookalike text from an earlier turn. */
internal class PrivateTextMask(
    categories: Set<PrivacyCategory>,
    privateTerms: List<String>,
    locationPolicy: PrivateLocationPolicy = PrivateLocationPolicy.REDACT_RESIDENCE_KEEP_SEARCH_REGION
) {
    private val prefix = "[PRIVATE_" + UUID.randomUUID().toString().replace("-", "") + "_"
    private val replacements = linkedMapOf<String, String>()
    private val issued = linkedSetOf<String>()
    private val detector = PrivateTextDetector(categories, privateTerms, locationPolicy)
    private val categoryCounts = linkedMapOf<String, Int>()
    private var inspectedSurfaces = 0
    private var originalCharacters = 0L
    private var protectedCharacters = 0L
    private var transformedCharacters = 0L
    private var sealed = false

    fun seedIdentities(inputs: Iterable<String>) {
        check(!sealed)
        detector.seed(inputs)
    }

    private fun marker(value: String, kind: String): String = replacements.getOrPut(value) {
        (prefix + kind + "_" + replacements.size + "]").also(issued::add)
    }

    /** Marker identities are safe to bind to dispatch; the local values never leave this object. */
    fun issuedMarkers(): Set<String> = issued.toSet()

    fun summary(): PrivateMaskSummary = PrivateMaskSummary(
        detectorRevision = PrivateTextDetector.REVISION,
        locationPolicy = detector.locationPolicy.name,
        inspectedSurfaces = inspectedSurfaces,
        originalCharacters = originalCharacters,
        protectedCharacters = protectedCharacters,
        transformedCharacters = transformedCharacters,
        generatedMarkerCount = issued.size,
        matchesByCategory = categoryCounts.toMap()
    )

    fun protect(input: String): String = protectWithReceipt(input).text

    fun protectWithReceipt(input: String): PrivateProtectedText {
        check(!sealed)
        val detections = detector.detect(input)
        detections.forEach { categoryCounts[it.category.name] = (categoryCounts[it.category.name] ?: 0) + 1 }
        val spans = mutableListOf<PrivateTextDetection>()
        detections.sortedWith(compareBy<PrivateTextDetection> { it.range.first }.thenByDescending { it.range.last }).forEach { detection ->
            val previous = spans.lastOrNull()
            if (previous != null && detection.range.first <= previous.range.last) {
                val kind = when {
                    previous.range.last >= detection.range.last -> previous.kind
                    previous.range.first == detection.range.first -> detection.kind
                    previous.kind == detection.kind -> previous.kind
                    else -> "SENSITIVE"
                }
                spans[spans.lastIndex] = previous.copy(range = previous.range.first..maxOf(previous.range.last, detection.range.last), kind = kind)
            } else {
                spans += detection
            }
        }
        val ranges = mutableListOf<PrivacyProtectedRange>()
        val text = buildString {
            var previousEnd = 0
            spans.forEach { span ->
                append(input, previousEnd, span.range.first)
                val end = span.range.last + 1
                ranges += PrivacyProtectedRange(span.range.first, end)
                append(marker(input.substring(span.range.first, end), span.kind))
                previousEnd = end
            }
            append(input, previousEnd, input.length)
        }
        inspectedSurfaces++
        originalCharacters += input.length
        protectedCharacters += ranges.sumOf { (it.endExclusive - it.start).toLong() }
        transformedCharacters += text.length
        return PrivateProtectedText(text, PrivacyProtectionReceipt.create(input, ranges))
    }

    fun sealForResponse(): (String) -> String {
        val restore = sealWithReceipts()
        return { restore(it).text }
    }

    fun sealWithReceipts(): (String) -> PrivateProtectedText {
        check(!sealed)
        sealed = true
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val plaintext = Json.encodeToString(replacements.entries.associate { it.value to it.key }).encodeToByteArray()
        val ciphertext = try {
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val iv = cipher.iv
        replacements.clear()
        detector.clear()
        val markerPrefix = prefix
        val markerPattern = Regex(Regex.escape(markerPrefix) + "[A-Z][A-Z_]{0,31}_\\d{1,10}]")
        return restore@{ answer ->
            // Most streamed chunks contain public text. Avoid decrypting the map
            // unless this request's exact namespace can actually be present.
            if (!answer.contains(markerPrefix)) return@restore PrivateProtectedText(answer, PrivacyProtectionReceipt.create(answer, emptyList()))
            val reader = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            val bytes = reader.doFinal(ciphertext)
            try {
                val values = Json.decodeFromString<Map<String, String>>(bytes.decodeToString())
                val ranges = mutableListOf<PrivacyProtectedRange>()
                val restored = buildString {
                    var previousEnd = 0
                    markerPattern.findAll(answer).forEach { match ->
                        append(answer, previousEnd, match.range.first)
                        // The complete issued token, including its type, is authoritative.
                        // An altered or foreign marker can never select a local value.
                        val value = values[match.value]
                        if (value == null) {
                            append(match.value)
                        } else {
                            val start = length
                            append(value)
                            ranges += PrivacyProtectedRange(start, length)
                        }
                        previousEnd = match.range.last + 1
                    }
                    append(answer, previousEnd, answer.length)
                }
                PrivateProtectedText(restored, PrivacyProtectionReceipt.create(restored, ranges))
            } finally {
                bytes.fill(0)
            }
        }
    }
}

/** Counts only; no original text, dictionary, hashes of individual secrets, or inferred success rate. */
internal data class PrivateMaskSummary(
    val detectorRevision: String,
    val locationPolicy: String,
    val inspectedSurfaces: Int,
    val originalCharacters: Long,
    val protectedCharacters: Long,
    val transformedCharacters: Long,
    val generatedMarkerCount: Int,
    val matchesByCategory: Map<String, Int>,
    val coverage: String = "DETECTED_SPANS_ONLY"
)
