package dev.chungjungsoo.gptmobile.data.audit

import java.security.MessageDigest

internal enum class AppAuditPluginKind(val area: String) {
    INTEGRATED("agent_tools"),
    NATIVE("marketplace_plugins"),
    MCP("mcp_plugins"),
    GATEWAY("gateway_client")
}

/** Ephemeral service references never enter a persisted run or its export. */
internal data class AppAuditPluginTarget(
    val key: String,
    val kind: AppAuditPluginKind,
    val componentId: String,
    val configured: Boolean,
    val enabled: Boolean,
    val fingerprint: String,
    val unavailableReason: String = "PLUGIN_NOT_CONFIGURED",
    val liveReadPrecheck: AppAuditObservation? = null,
    val liveRead: (suspend () -> AppAuditObservation)? = null,
    val snapshotCurrent: () -> Boolean = { true }
)

/** Existing clients own transport, credentials, retries and their physical request limits. */
class AppAuditPluginProbeSource internal constructor(
    private val inventory: suspend (selectedProfileIds: List<String>) -> List<AppAuditPluginTarget>,
    private val targetLookup: (suspend (key: String) -> AppAuditPluginTarget?)? = null
) : AppAuditProbeSource {
    override val sourceId: String = SOURCE_ID

    override suspend fun plan(options: AppAuditOptions): List<AppAuditPlanEntry> {
        val targets = inventory(options.selectedProfileIds.distinct()).distinctBy { it.key }.sortedBy { it.key }
        check(targets.all { it.snapshotCurrent() }) { "Plugin configuration changed during inventory." }
        return buildList {
            targets.take(MAX_TARGETS).forEach { target ->
                add(entry(target, CONFIGURATION, options))
                if (target.kind in setOf(AppAuditPluginKind.MCP, AppAuditPluginKind.GATEWAY)) {
                    add(entry(target, if (target.kind == AppAuditPluginKind.MCP) MCP_DISCOVERY else GATEWAY_CAPABILITIES, options))
                }
                // A catalog or installed registration cannot establish any tool's behavior.
                if (target.kind != AppAuditPluginKind.GATEWAY && target.componentId !in LOCAL_FIXTURE_COMPONENTS) {
                    add(entry(target, TOOL_EXECUTION, options))
                }
            }
            if (targets.size > MAX_TARGETS) {
                add(
                    AppAuditPlanEntry(
                        id = "$SOURCE_ID:$INVENTORY_LIMIT",
                        sourceId = SOURCE_ID,
                        probeId = INVENTORY_LIMIT,
                        componentId = "plugins",
                        label = "Additional plugin targets",
                        targetId = "omitted:${targets.size - MAX_TARGETS}",
                        sourceAreaIds = listOf("agent_tools", "mcp_plugins", "marketplace_plugins", "gateway_client"),
                        reasonCode = "PLUGIN_TARGET_LIMIT"
                    )
                )
            }
        }
    }

    private fun entry(target: AppAuditPluginTarget, probe: String, options: AppAuditOptions): AppAuditPlanEntry {
        val network = probe in setOf(MCP_DISCOVERY, GATEWAY_CAPABILITIES)
        val executionGap = probe == TOOL_EXECUTION
        return AppAuditPlanEntry(
            id = "$SOURCE_ID:$probe:${appAuditTargetDigest(target.key)}",
            sourceId = SOURCE_ID,
            probeId = probe,
            componentId = target.componentId,
            label = labels.getValue(probe),
            targetId = target.key,
            sourceAreaIds = listOf(target.kind.area),
            evidenceKind = when {
                network -> AppAuditEvidenceKind.LIVE_METADATA
                executionGap -> AppAuditEvidenceKind.TOOL_EXECUTION
                else -> AppAuditEvidenceKind.CONFIGURATION
            },
            safetyClass = when {
                network -> AppAuditSafetyClass.NETWORK_READ
                executionGap -> AppAuditSafetyClass.EXTERNAL_ONLY
                else -> AppAuditSafetyClass.LOCAL_READ
            },
            selected = !network || options.allowNetworkReads,
            prerequisite = when {
                executionGap -> AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED
                !target.configured -> AppAuditPrerequisite.NOT_CONFIGURED
                !target.enabled -> AppAuditPrerequisite.PERMISSION_REQUIRED
                else -> AppAuditPrerequisite.READY
            },
            reasonCode = when {
                executionGap -> "PLUGIN_EXECUTION_NOT_EXERCISED"
                network && !options.allowNetworkReads -> "NETWORK_READS_NOT_SELECTED"
                !target.configured -> target.unavailableReason
                !target.enabled -> "PLUGIN_DISABLED"
                else -> null
            },
            configFingerprint = target.fingerprint
        )
    }

    override suspend fun execute(entry: AppAuditPlanEntry, context: AppAuditProbeContext): AppAuditObservation {
        context.ensureActive()
        require(entry.sourceId == SOURCE_ID && entry.probeId in labels) { "Unknown plugin audit check." }
        if (entry.probeId == TOOL_EXECUTION || entry.probeId == INVENTORY_LIMIT) {
            return observation(AppAuditOutcome.UNTESTED, if (entry.probeId == INVENTORY_LIMIT) "PLUGIN_TARGET_LIMIT" else "PLUGIN_EXECUTION_NOT_EXERCISED", entry.evidenceKind).copy(
                metrics = if (entry.probeId == INVENTORY_LIMIT) {
                    entry.targetId?.removePrefix("omitted:")?.toIntOrNull()?.takeIf { it > 0 }
                        ?.let { listOf(AppAuditMetric(AppAuditMetricName.TARGET_COUNT, it.toDouble())) }.orEmpty()
                } else {
                    emptyList()
                }
            )
        }
        if (!entry.selected) return observation(AppAuditOutcome.SKIPPED, "NETWORK_READS_NOT_SELECTED", entry.evidenceKind)
        val target = currentTarget(entry) ?: return changed(entry)
        if (!target.configured) return observation(AppAuditOutcome.UNAVAILABLE, target.unavailableReason, entry.evidenceKind)
        if (!target.enabled) return observation(AppAuditOutcome.BLOCKED, "PLUGIN_DISABLED", entry.evidenceKind)
        if (entry.probeId == CONFIGURATION) {
            return observation(AppAuditOutcome.PASS, "PLUGIN_CONFIGURATION_ONLY", AppAuditEvidenceKind.CONFIGURATION).copy(
                observationSource = AppAuditObservationSource.CONFIGURATION,
                metrics = listOf(AppAuditMetric(AppAuditMetricName.TARGET_COUNT, 1.0))
            )
        }
        if (entry.safetyClass != AppAuditSafetyClass.NETWORK_READ || target.liveRead == null) {
            return observation(AppAuditOutcome.UNTESTED, "PLUGIN_EXECUTION_NOT_EXERCISED", entry.evidenceKind)
        }
        target.liveReadPrecheck?.let { return it }
        // The durable marker can suspend. Revalidate the frozen identity again afterwards.
        context.beforeDispatch()
        val admitted = currentTarget(entry) ?: return changed(entry)
        if (!target.snapshotCurrent()) return changed(entry)
        val read = admitted.liveRead ?: return changed(entry)
        if (!admitted.configured || !admitted.enabled) return changed(entry)
        admitted.liveReadPrecheck?.let { return it }
        val result = read()
        context.ensureActive()
        if (!admitted.snapshotCurrent() || currentTarget(entry) == null) return result.copy(configChanged = true)
        return result
    }

    private suspend fun currentTarget(entry: AppAuditPlanEntry): AppAuditPluginTarget? {
        val key = entry.targetId ?: return null
        val profiles = if (key.startsWith(GATEWAY_TARGET_PREFIX)) listOf(key.removePrefix(GATEWAY_TARGET_PREFIX)) else emptyList()
        val current = if (targetLookup != null) targetLookup.invoke(key) else inventory(profiles).singleOrNull { it.key == key }
        return current?.takeIf {
            it.snapshotCurrent() &&
                it.fingerprint == entry.configFingerprint &&
                it.componentId == entry.componentId &&
                entry.id == "$SOURCE_ID:${entry.probeId}:${appAuditTargetDigest(it.key)}" &&
                (entry.probeId != MCP_DISCOVERY || it.kind == AppAuditPluginKind.MCP) &&
                (entry.probeId != GATEWAY_CAPABILITIES || it.kind == AppAuditPluginKind.GATEWAY)
        }
    }

    private fun changed(entry: AppAuditPlanEntry) =
        observation(AppAuditOutcome.BLOCKED, "PLUGIN_CONFIGURATION_CHANGED", entry.evidenceKind).copy(configChanged = true)

    private fun observation(outcome: AppAuditOutcome, reason: String, evidence: AppAuditEvidenceKind) = AppAuditObservation(
        outcome,
        reason,
        stage = "plugin_precheck",
        evidenceKind = evidence,
        observationSource = AppAuditObservationSource.NONE
    )

    companion object {
        const val SOURCE_ID = "plugins"
        const val CONFIGURATION = "plugin_configuration"
        const val MCP_DISCOVERY = "mcp_discovery"
        const val GATEWAY_CAPABILITIES = "gateway_capabilities"
        const val TOOL_EXECUTION = "plugin_tool_execution"
        const val INVENTORY_LIMIT = "plugin_inventory_limit"
        internal const val GATEWAY_TARGET_PREFIX = "gateway:"
        internal const val MAX_TARGETS = 512
        internal val LOCAL_FIXTURE_COMPONENTS = setOf("calculator", "current_date")
        val labels = mapOf(
            CONFIGURATION to "Plugin configuration",
            MCP_DISCOVERY to "MCP protocol and tool discovery",
            GATEWAY_CAPABILITIES to "Selected gateway capability contract",
            TOOL_EXECUTION to "Plugin tool execution coverage",
            INVENTORY_LIMIT to "Additional plugin targets"
        )
        internal val gatewayDecodeReasons = setOf("MALFORMED_JSON", "SCHEMA_MISMATCH", "NULL_NOT_ALLOWED", "EXPECTED_BOOLEAN", "EXPECTED_INTEGER", "EXPECTED_STRING", "EXPECTED_OBJECT", "MISSING_REQUIRED_FIELD", "EXPECTED_ARRAY")
        val reasonCodes = setOf(
            "PLUGIN_NOT_CONFIGURED", "PLUGIN_NOT_INSTALLED", "PLUGIN_DISABLED", "PLUGIN_CONFIGURATION_ONLY", "PLUGIN_CONFIGURATION_CHANGED",
            "PLUGIN_CREDENTIAL_IDENTITY_UNAVAILABLE",
            "PLUGIN_EXECUTION_NOT_EXERCISED", "PLUGIN_TARGET_LIMIT", "NETWORK_READS_NOT_SELECTED",
            "PLUGIN_REGISTRY_UNAVAILABLE", "PLUGIN_PACKAGE_NOT_VERIFIED", "GATEWAY_PROFILE_UNSUPPORTED",
            "GATEWAY_PROFILE_UNAVAILABLE", "MCP_DISCOVERY_VERIFIED", "MCP_EMPTY_CATALOG", "MCP_DISCOVERY_UNAVAILABLE",
            "MCP_RECONNECT_REQUIRED", "MCP_BACKOFF_ACTIVE", "GATEWAY_CAPABILITY_VERIFIED", "GATEWAY_NOT_SUPPORTED",
            "GATEWAY_CAPABILITY_UNAVAILABLE", "GATEWAY_CAPABILITY_DECODE_REJECTED", "TLS_FAILURE", "DNS_FAILED",
            "DNS_RECOVERY", "ROUTE_UNAVAILABLE", "CONNECTION_REFUSED", "AUTHENTICATION_FAILED", "CONNECT_TIMEOUT",
            "HTTP_ERROR", "MCP_HANDSHAKE_FAILED", "STREAM_INTERRUPTED", "STREAM_IDLE_TIMEOUT", "REQUEST_TIMEOUT",
            "MCP_AUTHENTICATION_REQUIRED", "MCP_ENDPOINT_CONFIGURATION", "MCP_CATALOG_TIMEOUT", "MCP_FAILURE_UNCLASSIFIED", "MCP_PROTOCOL_VERSION_UNSUPPORTED",
            "MCP_INITIALIZATION_SCHEMA_INVALID", "MCP_DISCOVERY_SCHEMA_INVALID", "MCP_DISCOVERY_CONFIGURATION",
            "MCP_CATALOG_SCHEMA_INVALID", "MCP_CATALOG_LIMIT_EXCEEDED", "MCP_RESPONSE_ID_MISMATCH", "MCP_RESPONSE_SCHEMA_INVALID"
        ) + gatewayDecodeReasons.map { "GATEWAY_CAPABILITY_DECODE_$it" }
        val stages = setOf("plugin_precheck", "plugin_configuration", "mcp_discovery", "gateway_capabilities")
    }
}

internal fun appAuditTargetDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
