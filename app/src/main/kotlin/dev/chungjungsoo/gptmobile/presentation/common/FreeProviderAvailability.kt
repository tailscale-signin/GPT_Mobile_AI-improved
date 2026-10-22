package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import dev.chungjungsoo.gptmobile.data.free.FreeGenerationReadiness
import dev.chungjungsoo.gptmobile.data.free.FreeServiceStatus
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
internal fun freeProviderClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1000)
        value = System.currentTimeMillis()
    }
}

internal fun freeProviderAvailability(provider: FreeAiProvider, model: String, status: FreeServiceStatus?, now: Long): String? {
    val cooldown = FreeAiRequestLimiter.shared.cooldownUntilMillis(provider, model)
    if (cooldown != null && cooldown > now) {
        val seconds = (cooldown - now + 999) / 1000
        return "Rate limited until ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cooldown))} · ${seconds / 3600}h ${(seconds / 60) % 60}m ${seconds % 60}s remaining. Choose another provider; availability will be rechecked on your next request."
    }
    if (status?.readiness == FreeGenerationReadiness.RATE_LIMITED) return "Cooldown ended · availability will be rechecked on your next request."
    if (status?.readiness == FreeGenerationReadiness.ACCESS_DENIED) return "Access denied · ${if ((status.retryAt ?: 0) > now && status.deniedModel == model) "temporarily unavailable for generation · " else ""}choose another provider or retry later."
    return status?.readiness?.takeUnless { it == FreeGenerationReadiness.UNTESTED }?.label
}
