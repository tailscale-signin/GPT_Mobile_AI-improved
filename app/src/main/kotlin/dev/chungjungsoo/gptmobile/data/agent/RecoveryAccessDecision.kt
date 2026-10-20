package dev.chungjungsoo.gptmobile.data.agent

internal data class RecoveryAccessDecision(val allowed: Boolean, val reasonCode: String = if (allowed) "AUTHORIZED" else "SOURCE_PERMISSION_REVOKED", val affectedIds: List<String> = emptyList())
