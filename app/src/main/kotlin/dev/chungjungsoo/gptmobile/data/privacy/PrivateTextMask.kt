package dev.chungjungsoo.gptmobile.data.privacy

import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Request-scoped markers cannot accidentally restore lookalike text from an earlier turn. */
internal class PrivateTextMask(private val categories: Set<PrivacyCategory>, private val privateTerms: List<String>) {
    private val prefix = "[PRIVATE_" + UUID.randomUUID().toString().replace("-", "") + "_"
    private val replacements = linkedMapOf<String, String>()

    private fun marker(value: String): String = replacements.getOrPut(value) { prefix + replacements.size + "]" }

    fun protect(input: String): String {
        val hidden = BooleanArray(input.length)
        fun cover(pattern: Regex) {
            pattern.findAll(input).forEach { match ->
                hidden.fill(true, match.range.first, match.range.last + 1)
            }
        }
        if (PrivacyCategory.CUSTOM in categories) {
            privateTerms.filter(String::isNotBlank).distinct().forEach { cover(Regex(Regex.escape(it), RegexOption.IGNORE_CASE)) }
        }
        for ((category, pattern) in patterns) if (category in categories) cover(pattern)
        return buildString {
            var start = 0
            while (start < input.length) {
                var end = start + 1
                while (end < input.length && hidden[end] == hidden[start]) end++
                if (hidden[start]) append(marker(input.substring(start, end))) else append(input, start, end)
                start = end
            }
        }
    }

    fun sealForResponse(): (String) -> String {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val plaintext = Json.encodeToString(replacements.keys.toList()).encodeToByteArray()
        val ciphertext = try {
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val iv = cipher.iv
        replacements.clear()
        val markerPattern = Regex(Regex.escape(prefix) + "(\\d+)]")
        return { answer ->
            val reader = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            val bytes = reader.doFinal(ciphertext)
            try {
                val values = Json.decodeFromString<List<String>>(bytes.decodeToString())
                markerPattern.replace(answer) { it.groupValues[1].toIntOrNull()?.let(values::getOrNull) ?: it.value }
            } finally {
                bytes.fill(0)
            }
        }
    }

    companion object {
        private val patterns = listOf(
            PrivacyCategory.SECRETS to Regex("""(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----"""),
            PrivacyCategory.SECRETS to Regex("""(?i)\b(?:Bearer\s+[A-Za-z0-9._~+/=-]+|(?:api[_ -]?key|password|secret|token)\s*[:=]\s*\S+|(?:sk-|ghp_|github_pat_)[\w-]{8,})"""),
            PrivacyCategory.CONTACTS to Regex("""(?i)[\w.+%-]+@[\w.-]+\.[a-z]{2,}|(?<!\w)@[a-z0-9_]{2,}|\+?\d[\d ().-]{7,}\d"""),
            PrivacyCategory.NETWORK to Regex("""(?:\b(?:\d{1,3}\.){3}\d{1,3}\b|\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b|[A-Za-z]:\\[^\n,;]+|/(?:home|Users|data/user)/[^\s,;]+)"""),
            PrivacyCategory.LOCATIONS to Regex("""(?i)\b\d{1,6}\s+(?:[\p{L}.'-]+\s+){1,5}(?:street|avenue|road|drive|lane|blvd|st|ave|rd)\b|[-+]?\d{1,2}\.\d{3,},\s*[-+]?\d{1,3}\.\d{3,}"""),
            PrivacyCategory.PRIVATE_DATES to Regex("""(?i)\b(?:\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4}|(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\s+\d{1,2}(?:,\s*\d{4})?)\b"""),
            PrivacyCategory.FINANCES to Regex("""(?:[$€£]\s*\d[\d,.]*|\b(?:\d[ -]?){13,19}\b|(?i:\b(?:account|iban|routing)\s*(?:number|no\.?)?\s*[:#=]?\s*[A-Z0-9-]{5,}))"""),
            PrivacyCategory.DOCUMENT_IDS to Regex("""(?i)\b(?:passport|ssn|sin|employee\s*id|patient\s*id|student\s*id|license)\s*[:#=]?\s*[\w-]+|\b\d{3}[- ]\d{2,3}[- ]\d{3,4}\b"""),
            PrivacyCategory.HEALTH to Regex("""(?i)\b(?:diagnos(?:is|ed)|medication|allerg(?:y|ic)|pregnan(?:cy|t)|medical history|spouse|daughter|son|partner|wife|husband)\b[^\n.!?]*"""),
            PrivacyCategory.CONFIDENTIAL_WORK to Regex("""(?i)\b(?:confidential|client|project|codename|under NDA)\s+[^.!?\n]+"""),
            PrivacyCategory.IDENTITIES to Regex("""\b[\p{Lu}][\p{L}'’-]+(?:\s+[\p{Lu}][\p{L}'’-]+){1,3}\b|(?i:\b(?:my name is|named|name\s*:|call me)\s+)[\p{L}'’-]+""")
        )
    }
}
