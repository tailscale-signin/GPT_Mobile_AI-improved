package dev.chungjungsoo.gptmobile.data.agent

/** A narrow routing hint, never a claim that the answer is correct or needs no validation. */
internal enum class RoutineTaskKind { GREETING, INLINE_TEXT_TRANSFORMATION }

internal object RoutineTaskPolicy {
    private val greeting = Regex("(?i)^(?:hi|hello|hey|thanks|thank you|good morning|good afternoon|good evening)[!.\\s]*$")
    private val transformation = Regex("(?is)^(?:please\\s+)?(?:rephrase|reword|rewrite|proofread|fix (?:the )?grammar|correct (?:the )?spelling)(?:\\s+(?:this|the following|this sentence|this text))?\\s*:\\s*\\S")
    private val translation = Regex("(?is)^(?:please\\s+)?translate(?:\\s+this)?\\s+(?:to|into)\\s+[\\p{L}][\\p{L}\\s-]{0,40}:\\s*\\S")
    private val externalOrAdditionalWork = Regex("(?i)https?://|www\\.|\\b(?:attachments?|attached|files?|documents?|spreadsheets?|images?|repositories|repository|then|also|afterwards)\\b")

    fun classify(request: String): RoutineTaskKind? {
        val text = request.trim()
        if (!CombinedRequestScope.isStraightforward(text) || CombinedRequestScope.requestsExplicitParticipants(text)) return null
        if (greeting.matches(text)) return RoutineTaskKind.GREETING
        if (externalOrAdditionalWork.containsMatchIn(text)) return null
        return RoutineTaskKind.INLINE_TEXT_TRANSFORMATION.takeIf {
            transformation.containsMatchIn(text) || translation.containsMatchIn(text)
        }
    }
}
