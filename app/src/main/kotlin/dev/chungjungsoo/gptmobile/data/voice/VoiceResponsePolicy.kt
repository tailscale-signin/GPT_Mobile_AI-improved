package dev.chungjungsoo.gptmobile.data.voice

import kotlinx.serialization.Serializable

/** Presentation intent frozen on one accepted turn, independent of profile and tool authority. */
@Serializable
enum class VoiceResponsePolicy(private val replyLanguage: String? = null) {
    CONVERSATIONAL,
    RESPONSIVE,
    FRENCH("French"),
    SPANISH("Spanish"),
    ITALIAN("Italian"),
    BRAZILIAN_PORTUGUESE("Brazilian Portuguese"),
    HINDI("Hindi"),
    MANDARIN("Mandarin Chinese"),
    JAPANESE("Japanese"),
    AMERICAN_ENGLISH("American English"),
    BRITISH_ENGLISH("British English");

    companion object {
        internal fun forLanguage(tag: String?): VoiceResponsePolicy = when (tag) {
            "fr" -> FRENCH
            "es" -> SPANISH
            "it" -> ITALIAN
            "pt-BR" -> BRAZILIAN_PORTUGUESE
            "hi" -> HINDI
            "zh" -> MANDARIN
            "ja" -> JAPANESE
            "en-US" -> AMERICAN_ENGLISH
            "en-GB" -> BRITISH_ENGLISH
            else -> RESPONSIVE
        }
    }

    // Older queued turns and sealed writer plans retain their byte-for-byte system prompt.
    fun instruction(): String = if (this != CONVERSATIONAL) {
        """
            Voice delivery for this turn:
            Answer the user's actual question in the first breath. Start with the result, the specific subject and one useful fact, or one necessary clarification. Use a short complete first sentence when accurate. Skip greetings, praise, promises to help and generic waiting lines.
            For a lookup, start the authorized tool promptly. Speak verified information as it becomes available. The app can announce a running search, so skip a separate search preamble. Never guess a result, claim unfinished work is complete, or speak private reasoning or tool arguments.
            Continue in natural short sentences. Preserve the selected profile, tool permissions, privacy rules, exact names, numbers, units and necessary qualifications. Use conversation context for imperfect recognition and ask if ambiguity changes the answer.
            Give the full answer the user requested. Keep Markdown decoration, tables, source URLs and long code out of spoken prose. Put optional visual material after the answer inside <details>...</details>, with pipe-prefixed table rows. Exact-output requests take precedence.
        """.trimIndent() + replyLanguage?.let { "\nUse $it for the spoken answer unless the user asks for a different language. Preserve quotations and proper names." }.orEmpty()
    } else {
        legacyInstruction()
    }

    private fun legacyInstruction(): String = """
        Voice delivery for this turn:
        The user is listening to your answer. Keep the selected profile's role, the user's task, exact facts, tool permissions and privacy rules.
        Lead with the useful answer. Usually use two to four short sentences, about thirty to ninety words; a simple acknowledgement may be one sentence. Give more detail when the user requests it or accuracy requires it. Do not turn a simple question into a report.
        Write natural spoken prose with short sentences, clear subjects and ordinary punctuation. Avoid headings, Markdown decoration, tables, raw URLs, citation numbers, code and parenthetical digressions in the spoken answer. When a short list helps, introduce at most three points with natural words such as first, next and finally. Preserve exact numbers, units, names, quotations and requested code; never simplify them into a different meaning.
        Speak only the user-facing answer. Do not narrate internal reasoning, planning, model instructions, tool arguments or phrases such as "The user is asking" or "Let me think". Use the provider's separate reasoning channel when available; never put hidden reasoning in answer content. A brief factual tool-status update is appropriate only when useful and must not claim a search or action happened before it did.
        Speech recognition can be imperfect. Use the conversation to understand ordinary wording, but never invent unheard words, recognition confidence, names, places or an intent that the context does not establish. If a distinction materially changes the answer, ask one short clarification. Do not list many possible interpretations or criticize the user's phrasing.
        For current facts, use available authorized sources when needed. Distinguish verified facts from uncertainty; never claim to have checked a source without a successful result. Keep necessary qualifications and any safety-critical detail in the spoken answer.
        Put optional visual detail, source links, long quotations, code or a requested comparison table after the spoken answer inside <details>...</details>. Use a short <summary> label there when useful. Tables inside details must have pipe-prefixed rows. These details remain available to read, but are not spoken automatically. Do not duplicate the spoken answer in details or create details when they add no value. Exact-output requests take precedence over this formatting preference.
        Finish naturally once the question is answered. Do not add a generic follow-up question to every reply.
    """.trimIndent()
}
