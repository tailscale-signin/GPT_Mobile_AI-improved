package dev.chungjungsoo.gptmobile.data.privacy

/**
 * Request-local identity discovery. Seed every outgoing message before masking so that an
 * introduction in a later message also protects earlier references in the same request.
 */
internal class PrivateIdentityDetector {
    private val identities = linkedSetOf<String>()
    private val surnames = linkedSetOf<String>()
    private var references: Regex? = null

    fun seed(inputs: Iterable<String>) {
        var changed = false
        inputs.forEach { input ->
            introductions.forEach { pattern ->
                pattern.findAll(input).forEach { match ->
                    val name = match.groupValues[1].trim()
                    if (name.isNotEmpty() && name.lowercase() !in ordinaryIntroductions) {
                        changed = identities.add(name) || changed
                        // An explicitly introduced full name also establishes its given name.
                        changed = identities.add(name.substringBefore(' ').substringBefore('\t')) || changed
                        name.split(Regex("[ \\t]+")).takeIf { it.size > 1 }?.lastOrNull()?.let(surnames::add)
                    }
                }
            }
        }
        // A surname is an alias only in a person context, never just because it is
        // capitalized (May, Brown and similar words have ordinary meanings too).
        if (surnames.isNotEmpty()) {
            val names = surnames.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }
            val contextualSurname = Regex("(?i:(?:\\b(?:Mr|Mrs|Ms|Mx|Dr|Prof)\\.?[ \\t]+|\\b(?:Dear|Hi|Hello|Hey)[ \\t]+))($names)(?![$IDENTITY_CHARACTERS])|(?<![$IDENTITY_CHARACTERS])($names)(?=[ \\t]+(?i:said|wrote|replied|agreed|approved|asked|signed)\\b)")
            inputs.forEach { input ->
                contextualSurname.findAll(input).forEach { match ->
                    changed = identities.add(match.groupValues[1].ifEmpty { match.groupValues[2] }) || changed
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
        surnames.clear()
        references = null
    }

    companion object {
        private const val IDENTITY_CHARACTERS = "\\p{L}\\p{M}\\p{N}_'’\\-"
        private const val WORD = "[\\p{L}][\\p{L}\\p{M}'’\\-]*"
        private const val CAPITALIZED_WORD = "[\\p{Lu}\\p{Lt}\\p{Lo}][\\p{L}\\p{M}'’\\-]*"

        // Name fields may contain more than four parts. Horizontal separators keep
        // an unrelated heading/paragraph out of an identity; only explicit titles
        // below may precede a wrapped name on the next line.
        private const val STOP_WORDS = "and|or|i|we|are|is|was|were|will|would|have|has|my|our|your|the|a|an|said|says|from|with|who|hereinafter"
        private const val REMAINING_NAME = "(?:[ \\t]+(?!(?i:$STOP_WORDS)\\b)(?:(?i:de|da|dos|van|von|bin|ibn|al|el|du|del|la)[ \\t]+)?$CAPITALIZED_WORD)*+"
        private const val NAME = "$WORD$REMAINING_NAME"
        private const val CAPITALIZED_NAME = "$CAPITALIZED_WORD$REMAINING_NAME"

        // Explicit name declarations also accept all-lowercase names. Task and
        // sentence boundaries prevent the value from absorbing the following ask.
        private const val EXPLICIT_NAME_STOPS = "$STOP_WORDS|but|because|so|to|please|help|need|want|can|could|should|draft|write|reply|summarize|summarise|create|prepare|live|living|work|working|study|studying|currently"
        private const val EXPLICIT_NAME = "$WORD(?:[ \\t]+(?!(?i:$EXPLICIT_NAME_STOPS)\\b)$WORD)*+"
        private const val RELATIONS = "girlfriend|boyfriend|partner|spouse|wife|husband|mother|father|mom|mum|dad|daughter|son|sister|brother|fianc[ée]{1,2}|fiancee?|grandmother|grandfather|grandma|grandpa|aunt|uncle|cousin|child"
        private const val TITLE = "Mr|Mrs|Ms|Mx|Dr|Prof|Professor"
        private val ordinaryIntroductions = setOf("happy", "fine", "sorry", "ready", "here", "available", "looking", "going", "writing", "grateful", "pleased", "thankful")
        private val introductions = listOf(
            Regex("(?i:\\bmy name is[ \\t]+)($EXPLICIT_NAME)"),
            Regex("(?i:\\bcall me[ \\t]+)($NAME)"),
            Regex("(?im:^[ \\t]*(?:(?:full|legal|preferred|first|last)[ \\t]+)?name[ \\t]*:[ \\t]*)($EXPLICIT_NAME)"),
            Regex("(?i:\\b(?:i am|i'm|i’m)[ \\t]+)($CAPITALIZED_NAME)"),
            Regex("(?i:\\bi)\\s*,?\\s*\\(\\s*($NAME)\\s*\\)"),
            Regex("(?i:\\b(?:$RELATIONS)[ \\t]+(?:(?:is[ \\t]+)?(?:named|called)[ \\t]+))($NAME)"),
            Regex("(?i:\\b(?:person|friend|colleague|student|employee|consultant|contractor|author|patient)[ \\t]+(?:(?:is[ \\t]+)?(?:named|called)[ \\t]+))($NAME)"),
            Regex("(?i:\\b(?:$RELATIONS)(?:[ \\t]+is)?[ \\t]*[:,]?[ \\t]+)($CAPITALIZED_NAME)"),
            Regex("(?i:\\b(?:$RELATIONS)['’]s[ \\t]+name[ \\t]+is[ \\t]+)($NAME)"),
            Regex("(?im:^[ \\t]*(?:reply:[ \\t]*)?(?:dear|hello|hi|hey)[ \\t]+(?:(?:$TITLE)\\.?[ \\t]+)?)($CAPITALIZED_NAME)(?=[ \\t]*[,!:]|[ \\t]*$)"),
            Regex("(?im:^[ \\t]*(?:from|to|cc|bcc|signed by|signature|contact person|representative|employee name|student name)[ \\t]*:[ \\t]*)(?:(?i:$TITLE)\\.?[ \\t]+)?($CAPITALIZED_NAME)(?=[ \\t]*(?:<|$|[;,]))"),
            Regex("(?i:\\b(?:$TITLE)\\.?[ \\t]+)($CAPITALIZED_NAME)"),
            Regex("(?i:\\b(?:$TITLE)\\.[ \\t]*\\r?\\n[ \\t]*)($CAPITALIZED_NAME)"),
            Regex("(?m)^[ \\t]*($CAPITALIZED_NAME)[ \\t]+\\((?i:hereinafter[ \\t]+)?[\"“]?(?i:independent contractor|contractor|employee|consultant)[\"”]?")
        )
    }
}
