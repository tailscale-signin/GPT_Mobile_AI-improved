package dev.chungjungsoo.gptmobile.data.privacy

/**
 * Request-local identity discovery. Seed every outgoing message before masking so that an
 * introduction in a later message also protects earlier references in the same request.
 */
internal class PrivateIdentityDetector {
    private val identities = linkedSetOf<String>()
    private var references: Regex? = null

    fun seed(inputs: Iterable<String>) {
        var changed = false
        inputs.forEach { input ->
            introductions.forEach { pattern ->
                pattern.findAll(input).forEach { match ->
                    val name = match.groupValues[1].trim()
                    if (name.isNotEmpty()) {
                        changed = identities.add(name) || changed
                        // An explicitly introduced full name also establishes its given name.
                        changed = identities.add(name.substringBefore(' ').substringBefore('\t')) || changed
                    }
                }
            }
        }
        if (changed) {
            val values = identities.sortedByDescending(String::length).joinToString("|") { identity ->
                identity.split(Regex("[ \\t]+")).joinToString("[ \\t]+") { Regex.escape(it) }
            }
            references = Regex(
                "(?<![$IDENTITY_CHARACTERS])(?:$values)(?=$|[^$IDENTITY_CHARACTERS]|['’][sS](?![$IDENTITY_CHARACTERS]))",
                RegexOption.IGNORE_CASE
            )
        }
    }

    fun ranges(input: String): List<IntRange> {
        seed(listOf(input))
        return references?.findAll(input)?.map { it.range }?.toList().orEmpty()
    }

    fun clear() {
        identities.clear()
        references = null
    }

    companion object {
        private const val IDENTITY_CHARACTERS = "\\p{L}\\p{M}\\p{N}_'’\\-"
        private const val WORD = "[\\p{L}][\\p{L}\\p{M}'’\\-]*"
        private const val CAPITALIZED_WORD = "[\\p{Lu}\\p{Lt}\\p{Lo}][\\p{L}\\p{M}'’\\-]*"
        private const val REMAINING_NAME = "(?:[ \\t]+(?!(?i:and|or|i|we|are|is|was|were)\\b)$CAPITALIZED_WORD){0,3}"
        private const val NAME = "$WORD$REMAINING_NAME"
        private const val CAPITALIZED_NAME = "$CAPITALIZED_WORD$REMAINING_NAME"
        private const val RELATIONS = "girlfriend|boyfriend|partner|spouse|wife|husband|mother|father|mom|mum|dad|daughter|son|sister|brother|fianc[ée]{1,2}|fiancee?|grandmother|grandfather|grandma|grandpa|aunt|uncle|cousin|child"
        private val introductions = listOf(
            Regex("(?i:\\b(?:my name is|named|name\\s*:|call me)\\s+)($NAME)"),
            Regex("(?i:\\b(?:i am|i'm|i’m)\\s+)($CAPITALIZED_NAME)"),
            Regex("(?i:\\bi)\\s*,?\\s*\\(\\s*($NAME)\\s*\\)"),
            Regex("(?i:\\b(?:$RELATIONS)\\s+(?:(?:is\\s+)?(?:named|called)\\s+))($NAME)"),
            Regex("(?i:\\b(?:$RELATIONS)(?:\\s+is)?\\s*[:,]?\\s+)($CAPITALIZED_NAME)")
        )
    }
}
