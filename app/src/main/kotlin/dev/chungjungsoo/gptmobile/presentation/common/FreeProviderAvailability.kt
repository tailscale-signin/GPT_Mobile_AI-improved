package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import dev.chungjungsoo.gptmobile.data.free.FreeGenerationReadiness
import dev.chungjungsoo.gptmobile.data.free.FreeServiceStatus
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private object FreeProviderTicker {
    private val ticks = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1000)
        }
    }
    val time = combine(ticks, FreeAiRequestLimiter.shared.revision, dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.routeStatus) { tick, _, _ -> maxOf(tick, System.currentTimeMillis()) }
        .stateIn(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default), kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())
}

@Composable
internal fun freeProviderClock(): State<Long> = FreeProviderTicker.time.collectAsState()

internal fun freeProviderAvailability(provider: FreeAiProvider, model: String, status: FreeServiceStatus?, now: Long, token: String? = null): String? {
    provider.unavailableReason?.let { return it }
    val identity = dev.chungjungsoo.gptmobile.data.network.freeCredentialIdentity(provider, token)
    val (saved, cooldown) = try {
        val evidence = FreeAiRequestLimiter.shared.savedCooldown(provider, model, identity)
        evidence to if (evidence != null) FreeAiRequestLimiter.shared.cooldownUntilMillis(provider, model, identity) else FreeAiRequestLimiter.shared.exhaustedAllowanceUntilMillis(provider, model, identity)
    } catch (_: IllegalStateException) {
        return "Saved provider limits are unavailable"
    }
    if (cooldown != null && cooldown > now) {
        val minutes = ((cooldown - now + 59_999) / 60_000).coerceAtLeast(1)
        return if (saved?.deadlineSource == "local_estimate") "Rate limited: retry in about $minutes ${if (minutes == 1L) "minute" else "minutes"}" else "Rate limited: $minutes ${if (minutes == 1L) "minute" else "minutes"} left"
    }
    dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.generationUnavailableReason(provider, model, identity)?.let { return it }
    val status = dev.chungjungsoo.gptmobile.data.free.FreeCatalogState.generationStatus(provider, model, identity) ?: status?.takeIf { it.deniedModel == null && it.servedModel == null }
    if (status?.readiness == FreeGenerationReadiness.RATE_LIMITED) return if ((status.retryAt ?: 0) <= now) "Cooldown ended · availability will be rechecked on your next request." else "Provider reported a rate limit; this profile’s allowance will be checked before dispatch."
    if (status?.readiness == FreeGenerationReadiness.ACCESS_DENIED) return "Access denied · ${if ((status.retryAt ?: 0) > now && status.deniedModel == model) "temporarily unavailable for generation · " else ""}choose another provider or retry later."
    return status?.readiness?.takeUnless { it == FreeGenerationReadiness.UNTESTED }?.label
}
