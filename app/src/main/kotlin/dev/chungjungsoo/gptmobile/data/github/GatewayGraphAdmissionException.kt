package dev.chungjungsoo.gptmobile.data.github

/** Raised only before any graph request; no local policy rejection may trigger native fallback. */
internal class GatewayGraphAdmissionException(val code: String, message: String, val repairAction: String? = null) : IllegalStateException(message)
