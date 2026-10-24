package dev.chungjungsoo.gptmobile.data.network.error

/** Parser failures are output faults; transport interruption and output limits stay distinct. */
internal fun isOutputProtocolFailure(details: String): Boolean = outputProtocolFailureCode.containsMatchIn(details)

private val outputProtocolFailureCode = Regex(
    "\\b(?:OUTPUT_PROTOCOL_INVALID|INVALID_DELIVERY_RECORD|UNKNOWN_REFERENCE|UNKNOWN_SOURCE_REFERENCE|UNKNOWN_PROFILE_REFERENCE|UNKNOWN_CLAIM_REFERENCE|INVALID_RECORD_ID|CONFLICTING_RECORD_REPLAY|RECORD_AFTER_END|DELIVERY_SEQUENCE_GAP|INVALID_PASSAGE_RECORD|INVALID_PASSAGE_BODY|INVALID_UNICODE|INVALID_DECISION_RECORD|INVALID_CLAIM_DECISION|INVALID_END_RECORD|INVALID_RECORD_TYPE|MALFORMED_JSON_RECORD)\\b",
    RegexOption.IGNORE_CASE
)
