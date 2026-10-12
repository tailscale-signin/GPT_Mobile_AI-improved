package dev.chungjungsoo.gptmobile.data.network

/** Accept only an opaque identifier; never include headers containing credentials or line breaks. */
internal fun providerRequestIdentity(header: (String) -> String?): String = sequenceOf(
    "X-Request-ID",
    "Request-ID",
    "X-Generation-ID",
    "X-Gateway-Request-ID"
).mapNotNull(header).firstOrNull { it.length in 1..160 && it.all { char -> char.isLetterOrDigit() || char in "._:-" } } ?: "unavailable"
