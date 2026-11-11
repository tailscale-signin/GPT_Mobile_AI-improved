package dev.chungjungsoo.gptmobile.data.audit

/** An opaque saved profile snapshot. No model prompt, token or endpoint enters the audit. */
internal data class AppAuditSelectedModelTarget(
    val key: String,
    val configured: Boolean,
    val enabled: Boolean,
    val fingerprint: String,
    val configurationRevision: String?,
    val local: Boolean = false,
    val profileSnapshotHash: String? = null
)

/** Selecting a profile plans generation qualification; metadata checks never generate text. */
internal class AppAuditSelectedModelProbeSource(
    private val inventory: suspend (List<String>) -> List<AppAuditSelectedModelTarget>,
    private val lookup: suspend (String) -> AppAuditSelectedModelTarget?
) : AppAuditProbeSource {
    override val sourceId: String = SOURCE_ID

    override suspend fun plan(options: AppAuditOptions): List<AppAuditPlanEntry> = inventory(options.selectedProfileIds.distinct().sorted()).flatMap { target ->
        val configuration = AppAuditPlanEntry(
            id = "$SOURCE_ID:$CONFIGURATION:${appAuditTargetDigest(target.key)}",
            sourceId = SOURCE_ID,
            probeId = CONFIGURATION,
            componentId = "selected_model",
            label = labels.getValue(CONFIGURATION),
            targetId = target.key,
            sourceAreaIds = listOf("models_and_profiles", if (target.local) "local_inference" else "model_providers"),
            prerequisite = when {
                !target.configured -> AppAuditPrerequisite.NOT_CONFIGURED
                !target.enabled -> AppAuditPrerequisite.PERMISSION_REQUIRED
                else -> AppAuditPrerequisite.READY
            },
            reasonCode = when {
                !target.configured -> "MODEL_PROFILE_NOT_CONFIGURED"
                !target.enabled -> "MODEL_PROFILE_DISABLED"
                else -> null
            },
            configFingerprint = target.fingerprint,
            configurationRevision = target.configurationRevision
        )
        listOf(
            configuration,
            configuration.copy(
                id = "$SOURCE_ID:$GENERATION:${appAuditTargetDigest(target.key)}",
                probeId = GENERATION,
                label = labels.getValue(GENERATION),
                evidenceKind = AppAuditEvidenceKind.MODEL_EXECUTION,
                safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY,
                prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED,
                reasonCode = "MODEL_BENCHMARK_REQUIRED"
            )
        )
    }

    override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation {
        context.ensureActive()
        require(entry.sourceId == SOURCE_ID && entry.probeId in labels)
        if (entry.probeId == GENERATION) {
            return AppAuditObservation(AppAuditOutcome.UNTESTED, "MODEL_BENCHMARK_REQUIRED", evidenceKind = AppAuditEvidenceKind.MODEL_EXECUTION)
        }
        val uid = entry.targetId?.takeIf { it.startsWith(TARGET_PREFIX) }?.removePrefix(TARGET_PREFIX)
        val current = uid?.let { lookup(it) }
        val unchanged = current != null &&
            current.key == entry.targetId &&
            current.fingerprint == entry.configFingerprint &&
            current.configurationRevision != null &&
            current.configurationRevision == entry.configurationRevision &&
            entry.id == "$SOURCE_ID:$CONFIGURATION:${appAuditTargetDigest(current.key)}"
        return when {
            !unchanged -> AppAuditObservation(AppAuditOutcome.BLOCKED, "MODEL_CONFIGURATION_CHANGED", configChanged = true)
            !current.configured -> AppAuditObservation(AppAuditOutcome.UNAVAILABLE, "MODEL_PROFILE_NOT_CONFIGURED")
            !current.enabled -> AppAuditObservation(AppAuditOutcome.BLOCKED, "MODEL_PROFILE_DISABLED")
            else -> AppAuditObservation(
                AppAuditOutcome.PASS,
                "MODEL_CONFIGURATION_ONLY",
                observationSource = AppAuditObservationSource.CONFIGURATION,
                configurationRevision = current.configurationRevision
            )
        }
    }

    companion object {
        const val SOURCE_ID = "selected_models"
        const val TARGET_PREFIX = "model:"
        const val CONFIGURATION = "model_configuration"
        const val GENERATION = "model_generation"
        val labels = mapOf(CONFIGURATION to "Selected model configuration", GENERATION to "Selected model generation qualification")
        val reasonCodes = setOf("MODEL_PROFILE_NOT_CONFIGURED", "MODEL_PROFILE_DISABLED", "MODEL_CONFIGURATION_CHANGED", "MODEL_CONFIGURATION_ONLY", "MODEL_BENCHMARK_REQUIRED")
    }
}
