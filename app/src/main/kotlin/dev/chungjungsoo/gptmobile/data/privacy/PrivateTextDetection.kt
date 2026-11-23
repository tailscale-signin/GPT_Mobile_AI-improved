package dev.chungjungsoo.gptmobile.data.privacy

/** Coarse search regions remain useful; a declared residence is personal data. */
internal enum class PrivateLocationPolicy {
    REDACT_RESIDENCE_KEEP_SEARCH_REGION,
    REDACT_CONTEXTUAL_REGIONS
}

internal data class PrivateTextDetection(val range: IntRange, val category: PrivacyCategory, val kind: String)

/**
 * Local, bounded recognition of explicit fields and personal context. Capitalization
 * alone is never identity evidence. This is detected-span protection, not a claim
 * that an arbitrary document contains no further personal information.
 */
internal class PrivateTextDetector(
    private val categories: Set<PrivacyCategory>,
    private val privateTerms: List<String>,
    val locationPolicy: PrivateLocationPolicy
) {
    private val identities = PrivateIdentityDetector()
    private val privateDomains = linkedSetOf<String>()
    private val privatePlaces = linkedSetOf<String>()
    private val organizations = linkedSetOf<String>()

    fun seed(inputs: Iterable<String>) {
        val texts = inputs.toList()
        if (PrivacyCategory.IDENTITIES in categories) identities.seed(texts)
        texts.forEach { input ->
            if (PrivacyCategory.CONTACTS in categories) {
                email.findAll(input).forEach { match ->
                    val domain = match.value.substringAfter('@').lowercase()
                    if (domain !in commonMailDomains) privateDomains += domain
                }
                domainField.findAll(input).forEach { privateDomains += it.groupValues[1].lowercase().removePrefix("www.") }
            }
            if (PrivacyCategory.LOCATIONS in categories) {
                residencePatterns.forEach { pattern -> pattern.findAll(input).forEach { privatePlaces += it.groupValues[1] } }
                if (locationPolicy == PrivateLocationPolicy.REDACT_CONTEXTUAL_REGIONS) {
                    contextualRegion.findAll(input).forEach { privatePlaces += it.groupValues[1] }
                }
            }
            if (PrivacyCategory.CONFIDENTIAL_WORK in categories) {
                organizations += organizationNames(input)
                namedWork.findAll(input).forEach { match ->
                    val value = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
                    if (value.lowercase() !in ordinaryWorkLabels) organizations += value
                }
            }
        }
    }

    fun detect(input: String): List<PrivateTextDetection> {
        seed(listOf(input))
        return buildList {
            fun addMatches(category: PrivacyCategory, kind: String, pattern: Regex, group: Int = 0) {
                if (category !in categories) return
                pattern.findAll(input).forEach { match ->
                    match.groups[group]?.takeIf { it.value.isNotEmpty() }?.let { add(PrivateTextDetection(it.range, category, kind)) }
                }
            }
            fun addReferences(category: PrivacyCategory, kind: String, values: Set<String>) {
                values.sortedByDescending(String::length).forEach { value ->
                    addMatches(category, kind, Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(value) + "(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE))
                }
            }
            if (PrivacyCategory.CUSTOM in categories) {
                privateTerms.filter(String::isNotBlank).distinct().forEach { value ->
                    addMatches(PrivacyCategory.CUSTOM, "CUSTOM", Regex(Regex.escape(value), RegexOption.IGNORE_CASE))
                }
            }
            addMatches(PrivacyCategory.SECRETS, "SECRET", privateKey)
            addMatches(PrivacyCategory.SECRETS, "SECRET", secret)
            addMatches(PrivacyCategory.CONTACTS, "CONTACT", email)
            addMatches(PrivacyCategory.CONTACTS, "CONTACT", phoneOrHandle)
            if (PrivacyCategory.CONTACTS in categories && privateDomains.isNotEmpty()) {
                url.findAll(input).forEach { match ->
                    val host = match.groupValues[1].lowercase().removePrefix("www.")
                    if (privateDomains.any { host == it || host.endsWith(".$it") }) add(PrivateTextDetection(match.range, PrivacyCategory.CONTACTS, "CONTACT"))
                }
                addReferences(PrivacyCategory.CONTACTS, "CONTACT", privateDomains)
            }
            addMatches(PrivacyCategory.NETWORK, "NETWORK", network)
            addMatches(PrivacyCategory.LOCATIONS, "LOCATION", streetAddress)
            addMatches(PrivacyCategory.LOCATIONS, "LOCATION", coordinates)
            if (PrivacyCategory.LOCATIONS in categories) addAll(addressFields(input))
            if (addressContext.containsMatchIn(input)) addMatches(PrivacyCategory.LOCATIONS, "LOCATION", addressFragment)
            addReferences(PrivacyCategory.LOCATIONS, "LOCATION", privatePlaces)
            addMatches(PrivacyCategory.PRIVATE_DATES, "DATE", dates)
            addMatches(PrivacyCategory.PRIVATE_DATES, "DATE", relativeDate)
            addMatches(PrivacyCategory.PRIVATE_DATES, "DATE", deadline, 1)
            addMatches(PrivacyCategory.FINANCES, "FINANCIAL", money)
            addMatches(PrivacyCategory.FINANCES, "FINANCIAL", financialAccount, 1)
            addMatches(PrivacyCategory.FINANCES, "FINANCIAL", paymentCard)
            addMatches(PrivacyCategory.DOCUMENT_IDS, "IDENTIFIER", documentId, 1)
            addMatches(PrivacyCategory.DOCUMENT_IDS, "IDENTIFIER", formattedId)
            addReferences(PrivacyCategory.CONFIDENTIAL_WORK, "CONFIDENTIAL", organizations)
            if (PrivacyCategory.IDENTITIES in categories) {
                identities.ranges(input).forEach { add(PrivateTextDetection(it, PrivacyCategory.IDENTITIES, "PERSON")) }
            }
            if (PrivacyCategory.HEALTH in categories) addAll(healthContext(input))
        }.distinct()
    }

    fun clear() {
        identities.clear()
        privateDomains.clear()
        privatePlaces.clear()
        organizations.clear()
    }

    fun ordinaryModalRanges(input: String): List<IntRange> =
        if (PrivacyCategory.IDENTITIES in categories) identities.ordinaryModalRanges(input) else emptyList()

    private fun organizationNames(input: String): List<String> = buildList {
        // Scan once rather than backtracking over every capitalized suffix of a
        // long document. A legal/business suffix supplies the entity context.
        var start = -1
        var previousEnd = 0
        organizationWord.findAll(input).forEach { word ->
            if (word.value.lowercase() in organizationSeparators) {
                start = -1
                previousEnd = word.range.last + 1
                return@forEach
            }
            if (start < 0 || input.substring(previousEnd, word.range.first).any { it != ' ' && it != '\t' }) start = word.range.first
            if (start < word.range.first && organizationSuffix.matches(word.value)) add(input.substring(start, word.range.last + 1))
            previousEnd = word.range.last + 1
        }
    }

    private fun addressFields(input: String): List<PrivateTextDetection> = buildList {
        addressField.findAll(input).forEach { match ->
            val value = match.groups[1] ?: return@forEach
            // A same-line request is not part of the address. Keep common street
            // abbreviations intact while respecting actual sentence boundaries.
            val sentenceEnd = addressSentenceEnd.findAll(value.value).firstOrNull { boundary ->
                val word = value.value.substring(0, boundary.range.first).takeLastWhile(Char::isLetter).lowercase()
                boundary.value.first() != '.' || word !in addressAbbreviations
            }?.range?.first ?: value.value.length
            val requestStart = requestIntent.find(value.value)?.range?.first ?: value.value.length
            val end = value.value.substring(0, minOf(sentenceEnd, requestStart)).trimEnd(' ', '\t', ',').length
            if (end > 0) add(PrivateTextDetection(value.range.first until value.range.first + end, PrivacyCategory.LOCATIONS, "LOCATION"))
        }
    }

    private fun healthContext(input: String): List<PrivateTextDetection> = buildList {
        // An isolated relationship noun is not a health disclosure. Abstract only
        // a personal clause with a medical, distress, or caring-context cue, and
        // stop before a request such as "but I want to ask for an extension".
        clauses.findAll(input).forEach { clause ->
            val text = clause.value
            if (!personalSubject.containsMatchIn(text) || !healthCue.containsMatchIn(text)) return@forEach
            if (publicHealthTopic.containsMatchIn(text) && !personalHealthDisclosure.containsMatchIn(text)) return@forEach
            val task = requestIntent.find(text)
            val firstCue = healthCue.find(text) ?: return@forEach
            val from = if (task != null && task.range.first < firstCue.range.first) firstCue.range.first else 0
            val to = if (task != null && task.range.first > firstCue.range.last) task.range.first else text.length
            val candidate = text.substring(from, to)
            val leading = candidate.indexOfFirst { !it.isWhitespace() && it != ',' }.takeIf { it >= 0 } ?: return@forEach
            val trailing = candidate.indexOfLast { !it.isWhitespace() && it != ',' }
            if (trailing >= leading) {
                add(PrivateTextDetection((clause.range.first + from + leading)..(clause.range.first + from + trailing), PrivacyCategory.HEALTH, "HEALTH_CONTEXT"))
            }
        }
        // Structured medical attributes keep their labels and the surrounding task.
        medicalAttribute.findAll(input).forEach { match ->
            match.groups[1]?.takeIf { it.value.isNotBlank() }?.let { add(PrivateTextDetection(it.range, PrivacyCategory.HEALTH, "HEALTH_CONTEXT")) }
        }
    }

    companion object {
        const val REVISION = "contextual-private-spans-v2"
        private const val WORD = "[\\p{Lu}\\p{Lt}\\p{Lo}][\\p{L}\\p{M}'’\\-]*"
        private const val NAME = "$WORD(?:[ \\t]+(?!(?i:and|or|the|this|that|where|which|who|i|we|is|are|by|on|at|in|of)\\b)$WORD)*+"
        private const val MONTH = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
        private const val WEEKDAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
        private const val NUMBER = "\\d+(?:[ \\u00a0\\u202f.,]\\d+)*"
        private const val CURRENCY = "USD|CAD|EUR|GBP|AUD|NZD|CHF|CNY|JPY|INR|AED|SAR|BRL|MXN|KRW"
        private const val DOMAIN = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+"
        private val email = Regex("""(?i)[\w.+%-]+@[\w.-]+\.[a-z]{2,}""")
        private val phoneOrHandle = Regex("""(?i)(?<!\w)@[a-z0-9_]{2,}|\+?\d[\d ().-]{7,}\d""")
        private val url = Regex("(?i)(?:https?://)?((?:www\\.)?$DOMAIN)(?::\\d{1,5})?(?:/[^\\s<>\"()]*)?")
        private val domainField = Regex("(?i)\\b(?:website|domain|company site)[ \\t]*:[ \\t]*(?:https?://)?((?:www\\.)?$DOMAIN)")
        private val commonMailDomains = setOf("gmail.com", "outlook.com", "hotmail.com", "yahoo.com", "icloud.com", "proton.me", "protonmail.com")
        private val privateKey = Regex("""(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----""")
        private val secret = Regex("""(?i)\b(?:Bearer\s+[A-Za-z0-9._~+/=-]+|(?:api[_ -]?key|password|secret|token)\s*[:=]\s*\S+|(?:sk-|ghp_|github_pat_)[\w-]{8,})""")
        private val network = Regex("""(?:\b(?:\d{1,3}\.){3}\d{1,3}\b|\b(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}\b|[A-Za-z]:\\[^\n,;]+|/(?:home|Users|data/user)/[^\s,;]+)""")
        private val streetAddress = Regex("""(?i)\b\d{1,6}[ \t]+(?:[\p{L}.'’-]+[ \t]+){0,8}(?:street|avenue|road|drive|lane|boulevard|blvd|st|ave|rd)\b(?:[ \t]+(?:apt|unit|suite)\.?[ \t]*[\w-]+)?""")
        private val coordinates = Regex("""[-+]?\d{1,2}\.\d{3,},[ \t]*[-+]?\d{1,3}\.\d{3,}""")
        private val addressField = Regex("""(?im)\b(?:registered |postal |billing |home )?address(?:[ \t]*:[ \t]*|[ \t]+(?:is|at)[ \t]+)([^;\n]+)""")
        private val addressSentenceEnd = Regex("""[.!?](?=[ \t]|$)""")
        private val addressAbbreviations = setOf("st", "ave", "rd", "dr", "blvd", "apt", "ste", "dist", "no")
        private val addressContext = Regex("""(?i)\b(?:address|district|postal|zip|street|avenue|road)\b|\bdist\.""")
        private val addressFragment = Regex("\\b\\d{4,6}[ \\t,]+$NAME(?:,[ \\t]+$NAME)*(?=[ \\t]*(?:[.,;]|\\r?\\n|$))")
        private val residencePatterns = listOf(
            Regex("(?i:\\b(?:i|we|he|she|they)[ \\t]+(?:live|lives|reside|resides|am based|are based|is based)[ \\t]+(?:in|near|at)[ \\t]+)($NAME)"),
            Regex("(?i:\\b(?:my|our|his|her|their)[ \\t]+(?:home|residence|hometown)[ \\t]+(?:is[ \\t]+)?(?:in|near|at)[ \\t]+)($NAME)"),
            Regex("(?i:\\b(?:near|in)[ \\t]+)($NAME)(?=[ \\t]+(?i:(?:that|where)[ \\t]+(?:i|we)[ \\t]+(?:live|reside)\\b))")
        )
        private val contextualRegion = Regex("(?i:\\b(?:in|near|around|visit|visiting)[ \\t]+)($NAME)")
        private val dates = Regex("(?i)\\b(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|(?:$MONTH)[ \\t]+\\d{1,2}(?:st|nd|rd|th)?(?:,?[ \\t]+\\d{4})?|\\d{1,2}(?:st|nd|rd|th)?[ \\t]+(?:of[ \\t]+)?(?:$MONTH)(?:,?[ \\t]+\\d{4})?)\\b")
        private val relativeDate = Regex("(?i)\\b(?:next|this|last)[ \\t]+(?:$WEEKDAY|week|month|year)\\b")
        private val deadline = Regex("(?i)\\b(?:by|before|until|due|on)[ \\t]+($WEEKDAY)\\b")
        private val money = Regex("(?i)(?:(?<![\\p{L}\\p{N}])(?:[A-Z]{1,2}\\$|[$€£¥₹]|(?:$CURRENCY)\\b)[ \\t]*$NUMBER|(?<![\\p{L}\\p{N}])$NUMBER[ \\t]*(?:[A-Z]{1,2}\\$|[$€£¥₹]|(?:$CURRENCY)\\b))")
        private val financialAccount = Regex("""(?i)\b(?:account|iban|routing)[ \t]*(?:number|no\.?)?[ \t]*[:#=]?[ \t]*((?=[A-Z0-9-]{5,34}\b)(?=[A-Z0-9-]*\d)[A-Z0-9-]+)""")
        private val paymentCard = Regex("""(?<!\d)(?:\d[ -]?){13,19}(?!\d)""")
        private val documentId = Regex("""(?i)\b(?:passport|ssn|sin|employee[ \t]*id|patient[ \t]*id|student[ \t]*id|licen[cs]e|company[ \t]+registration|registration|CR)[ \t]*(?:number|no\.?)?[ \t]*[:#=]?[ \t]*((?=[A-Z0-9-]{4,34}\b)(?=[A-Z0-9-]*\d)[A-Z0-9-]+)""")
        private val formattedId = Regex("""\b\d{3}[- ]\d{2,3}[- ]\d{3,4}\b""")
        private val organizationWord = Regex("\\b$WORD\\b")
        private val organizationSuffix = Regex("(?i)(?:Publishing|Studios?|Labs?|Limited|Ltd|Inc|LLC|Corporation|Corp|University|College)")
        private val organizationSeparators = setOf("and", "or", "the", "this", "contract", "company", "registration", "independent", "contractor")
        private val namedWork = Regex("(?i:\\b(?:client|project|codename|company)[ \\t]*(?:(?:is|named|called)[ \\t]+|[:=][ \\t]*))(?:(?:[\"“])([^\"”\\n]+)[\"”]|($NAME))")
        private val ordinaryWorkLabels = setOf("name", "status", "scope", "done", "completed", "overview", "details", "registration", "agreement", "independent contractor")
        private val clauses = Regex("""[^.!?\n;]+""")
        private val personalSubject = Regex("""(?i)\b(?:i|me|my|we|our|he|she|his|her|their|family|brothers?|sisters?|patient)\b""")

        // A direct family disclosure has a human subject and a complete predicate.
        // The boundary keeps noun phrases such as "child process" or "care instructions" readable.
        private const val FAMILY_HEALTH = """(?:my|our|his|her|their)[ \t]+(?:partner|spouse|girlfriend|boyfriend|wife|husband|parent|mother|father|brother|sister|son|daughter|child)[ \t]+(?:is[ \t]+expecting[ \t]+(?:a[ \t]+(?:child|baby)|twins)|(?:needs|requires)[ \t]+(?:(?:medical|nursing|personal)[ \t]+)?care)(?=$|[ \t]*[,;.!?]|[ \t]+(?:and|but|so|because|now|soon|today|tomorrow|this|next|in|on|at|for|from|after|before|until|during)\b)"""
        private val healthCue = Regex("""(?i)\b(?:$FAMILY_HEALTH|mental(?:ly| health)?|emotionally|diagnos(?:is|ed)|medication|allerg(?:y|ic)|pregnan(?:cy|t)|medical history|depress(?:ion|ed)|anxiety|suicid\w*|self[- ]harm|care(?:taker|giver)|carer|helpline|crisis|hospitali[sz]ed|(?:family|personal life)[^.?!\n;]{0,100}(?:rough patch|struggl|difficult|going through)|(?:not (?:completely )?fit|straight of mind|keep (?:themselves|himself|herself|myself) alive))\b""")
        private val publicHealthTopic = Regex("""(?i)\b(?:explain|define|teach|lecture|presentation|essay|class|research|general information)\b""")
        private val personalHealthDisclosure = Regex("""(?i)\b(?:$FAMILY_HEALTH|(?:my|our|his|her|their)[ \t]+(?:diagnosis|medication|allerg\w*|mental health|depression|anxiety|pregnancy|medical history)|(?:i|we|he|she|they)[ \t]+(?:am|are|is|have|has|was|were|feel|felt)|caretaker|caregiver|rough mentally|rough patch|keep (?:themselves|himself|herself|myself) alive)\b""")
        private val requestIntent = Regex("""(?i)\b(?:(?:i|we)[ \t]+(?:do[ \t]+)?(?:want|need|would like)[ \t]+(?:to[ \t]+)?(?:ask|request|reply|resubmit|submit|draft|write|know|help|assistance)|(?:want|need|would like)[ \t]+(?:help|assistance)[ \t]+(?:with[ \t]+)?(?:writing|drafting|replying|asking|submitting|to[ \t]+(?:write|draft|reply|ask|submit))|help[ \t]+(?:me|us)[ \t]+(?:to[ \t]+)?(?:write|draft|reply|ask|submit)|please[ \t]+(?:help|reply|write|summari[sz]e|draft|ask)|(?:could|can|would)[ \t]+you[ \t]+(?:help|reply|write|draft))\b|^[ \t]*(?i:write|draft|summari[sz]e|explain|create|prepare)[ \t]+""")
        private val medicalAttribute = Regex("""(?i)\b(?:diagnosis|medication|allergy|medical history)[ \t]*[:=][ \t]*([^\n.!?;,]+)""")
    }
}
