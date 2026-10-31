package dev.chungjungsoo.gptmobile.core

data class InferenceCandidate(val id: String, val onDevice: Boolean)
data class InferenceRoute(val profileId: String?, val reason: String)

/** Candidates must already pass the application's privacy, role and approval filters. */
object InferenceRouting {
    fun choose(candidates: List<InferenceCandidate>, selectedId: String, allowFallback: Boolean, localPressure: Boolean, complexTask: Boolean): InferenceRoute {
        val selected = candidates.firstOrNull { it.id == selectedId }
        if (selected != null && (!selected.onDevice || !localPressure)) return InferenceRoute(selected.id, "selected_profile")
        if (selectedId.isNotBlank() && !allowFallback) return InferenceRoute(null, if (localPressure && selected?.onDevice == true) "local_hardware_pressure" else "selected_profile_unavailable")
        val available = candidates.filterNot { localPressure && it.onDevice }
        val preferred = if (localPressure || complexTask) available.firstOrNull { !it.onDevice } else available.firstOrNull { it.onDevice }
        val next = preferred ?: available.firstOrNull()
        return InferenceRoute(
            next?.id,
            when {
                next == null -> "no_eligible_destination"
                localPressure -> "local_hardware_pressure"
                complexTask -> "task_complexity"
                else -> "automatic_local_preference"
            }
        )
    }
}
