package dev.chungjungsoo.gptmobile.data.agent

/** Stable persisted milestones; notices carry the friendly label to the existing chat status UI. */
enum class CombinedDeliveryPhase(val label: String) {
    COLLECTING("Collecting responses"),
    VALIDATING("Validating Combined inputs"),
    INSPECTING("Inspecting large contributions"),
    COMBINING("Combining contributions"),
    CHECKING("Checking Combined coverage"),
    PUBLISHING("Publishing Combined answer")
}
