package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAuditQualificationTest {
    @Test fun everyInventoriedAreaHasConcreteCandidateSuitesWithoutPromotingUntestedCoverage() {
        val original = fixture(0, 0)
        val template = original.plan.first()
        val run = original.copy(
            plan = AppAuditSourceInventory.areas.map { area ->
                template.copy(id = "source-area:${area.id}", componentId = area.id, sourceAreaIds = listOf(area.id))
            }
        )
        val score = appAuditScore(run)
        val summary = appAuditSummary(run)
        val plan = qualification(run)
        val checks = plan["checks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(AppAuditSourceInventory.areas.size, checks.size)
        assertTrue(checks.all { it["namedHarnesses"]!!.jsonArray.isNotEmpty() })
        assertTrue(checks.all { it["namedHarnessesScope"]!!.jsonPrimitive.content.contains("neither verifies the entire source area") })
        assertTrue(checks.all { it["currentOutcome"]!!.jsonPrimitive.content == "PENDING" })
        assertEquals(score, appAuditScore(run))
        assertEquals(summary, appAuditSummary(run))
        assertFalse(plan["importsPromoteAuditScore"]!!.jsonPrimitive.boolean)
        val residual = checks.single { it["sourceAreaIds"]!!.jsonArray.contains(JsonPrimitive("other_production")) }
        assertTrue(residual["requirements"]!!.jsonArray.contains(JsonPrimitive("RESIDUAL_SOURCE_CASES")))
    }

    @Test fun missingOrDisabledTargetsGetSpecificActionsWithoutChangingTheAcceptedPlan() {
        val missing = fixture(1, 0)
        val before = Json.encodeToString(missing)
        val missingTarget = qualification(missing)["checks"]!!.jsonArray.first().jsonObject
        assertEquals("CONFIGURE_EXACT_TARGET_THEN_START_NEW_AUDIT", missingTarget["nextAction"]!!.jsonPrimitive.content)
        assertEquals(before, Json.encodeToString(missing))
        val disabled = missing.copy(plan = missing.plan.map { if (it.probeId == AppAuditPluginProbeSource.CONFIGURATION) it.copy(prerequisite = AppAuditPrerequisite.PERMISSION_REQUIRED) else it })
        val disabledTarget = qualification(disabled)["checks"]!!.jsonArray.first().jsonObject
        assertEquals("REVIEW_OPTIONAL_PLUGIN_PERMISSION_THEN_START_NEW_AUDIT", disabledTarget["nextAction"]!!.jsonPrimitive.content)
        assertEquals(AppAuditPrerequisite.PERMISSION_REQUIRED, disabled.plan.first().prerequisite)
    }

    @Test
    fun manifestAccountsForConfiguredAndMissingTargetsAndExternalSuitesWithoutGrantingExecution() {
        val run = fixture()
        val retained = Json.encodeToString(run)
        val plan = qualification(run)
        val checks = plan["checks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(49, checks.size)
        assertEquals(49, plan["pendingChecks"]!!.jsonPrimitive.int)
        assertEquals(25, checks.count { it["readiness"]!!.jsonPrimitive.content == "TARGET_FIXTURE_REQUIRED" })
        assertEquals(14, checks.count { it["readiness"]!!.jsonPrimitive.content == "CONFIGURATION_REQUIRED" })
        assertEquals(10, checks.count { it["requiredEvidenceKind"]!!.jsonPrimitive.content == "EXTERNAL_SUITE" })
        assertEquals("PLAN_ONLY", plan["authority"]!!.jsonPrimitive.content)
        assertFalse(plan["grantsExecution"]!!.jsonPrimitive.boolean)
        assertFalse(plan["importsPromoteAuditScore"]!!.jsonPrimitive.boolean)
        assertEquals(retained, Json.encodeToString(run))
        assertEquals(25, appAuditSummary(run).verified)
        assertEquals(100.0, appAuditScore(run).score!!, 0.0)
        val target = checks.first()
        assertEquals("target-1", target["targetAlias"]!!.jsonPrimitive.content)
        assertEquals("check-1", target["configurationCheckId"]!!.jsonPrimitive.content)
        assertEquals("NOT_OBSERVED_BY_THIS_AUDIT", target["remoteServiceIdentity"]!!.jsonPrimitive.content)
        val requirements = target["requirements"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue(requirements.containsAll(setOf("EXACT_CONFIGURATION", "READ_ONLY_OR_OWNED_FIXTURE", "NAMED_TOOL_AND_SCHEMA", "DISPATCH_RECEIPT", "VALIDATION_AND_DENIAL", "CANCELLATION_AND_RECOVERY", "OWNED_FIXTURE_CLEANUP", "REMOTE_SERVICE_OBSERVATION")))
        assertEquals(1, target["defaultMaxToolCalls"]!!.jsonPrimitive.int)
    }

    @Test
    fun qualificationBindingsChangeWithBuildConfigurationAndAcceptedPlan() {
        val run = fixture(1, 1)
        fun binding(value: AppAuditRun) = qualification(value)["checks"]!!.jsonArray.first().jsonObject["qualificationBinding"]!!.jsonPrimitive.content
        val original = binding(run)
        assertEquals(64, original.length)
        assertNotEquals(original, binding(run.copy(sourceCommit = "d".repeat(40))))
        assertNotEquals(original, binding(run.copy(catalogHash = "e".repeat(64))))
        assertNotEquals(original, binding(run.copy(plan = run.plan.map { it.copy(configurationRevision = "f".repeat(64)) })))
        val unknown = qualification(run.copy(sourceCommit = "unknown"))
        assertEquals(JsonNull, unknown["runBinding"])
        assertTrue(unknown["checks"]!!.jsonArray.all { it.jsonObject["qualificationBinding"] == JsonNull })
        val noRevision = qualification(run.copy(plan = run.plan.map { it.copy(configurationRevision = null) }))["checks"]!!.jsonArray.first().jsonObject
        assertEquals("CONFIGURATION_IDENTITY_UNAVAILABLE", noRevision["readiness"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, noRevision["qualificationBinding"])
    }

    @Test
    fun unsupportedExternalPassCannotRemoveARequiredQualificationOrEarnScore() {
        val run = fixture(1, 1)
        val tool = run.plan.first { it.evidenceKind == AppAuditEvidenceKind.TOOL_EXECUTION }
        val imported = AppAuditResult(
            tool.id,
            AppAuditObservation(AppAuditOutcome.PASS, "FIXTURE_PASSED", evidenceKind = tool.evidenceKind, observationSource = AppAuditObservationSource.LIVE, dispatchEvidence = AppAuditDispatchEvidence.CONFIRMED),
            1,
            2,
            1,
            true
        )
        val claimed = run.copy(results = run.results + imported)
        assertEquals(11, qualification(claimed)["pendingChecks"]!!.jsonPrimitive.int)
        assertEquals(appAuditScore(run), appAuditScore(claimed))
        assertEquals(appAuditSummary(run).verified, appAuditSummary(claimed).verified)
    }

    @Test
    fun namedExternalFamiliesKeepServiceModelNetworkAndResidualProofRequirements() {
        val checks = qualification(fixture(0, 0))["checks"]!!.jsonArray.map { it.jsonObject }.associateBy { it["family"]!!.jsonPrimitive.content }
        fun requirements(family: String) = checks.getValue(family)["requirements"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue("PROVIDER_STREAMING_CONTRACT" in requirements("PROVIDER_STREAMING_CONTRACT_SUITE"))
        assertTrue("MODEL_BENCHMARK" in requirements("PROVIDER_STREAMING_CONTRACT_SUITE"))
        assertTrue("NETWORK_RECOVERY" in requirements("NETWORK_TRANSPORT_RECOVERY_SUITE"))
        assertTrue("RENDERING_AND_ACCESSIBILITY" in requirements("DEVICE_RENDERING_ACCESSIBILITY_SUITE"))
        assertTrue("DATABASE_RECOVERY" in requirements("DATABASE_MIGRATION_RECOVERY_SUITE"))
        assertTrue("SERVICE_BUILD_IDENTITY" in requirements("GATEWAY_SERVICE_SUITE"))
        assertTrue("SERVICE_BUILD_IDENTITY" in requirements("AUTHENTICATION_SERVICE_SUITE"))
        assertTrue("RESIDUAL_SOURCE_CASES" in requirements("RESIDUAL_SOURCE_BEHAVIOR_SUITE"))
    }

    @Test
    fun worklistExportsAliasesAndFixedInstructionsWithoutPrivateTargetOrProviderText() {
        val private = "PRIVATE_TARGET_LABEL"
        val run = fixture(1, 1).let { value ->
            value.copy(plan = value.plan.map { it.copy(label = private, targetId = it.targetId?.let { private }, sourceAreaIds = it.sourceAreaIds + private, configFingerprint = private) })
        }
        val document = qualification(run)
        assertFalse(document.toString().contains(private))
        assertFalse(document.toString().contains("connection:"))
        assertFalse(document["grantsExecution"]!!.jsonPrimitive.boolean)
        assertEquals(1, appAuditSummary(run).verified)
    }

    @Test
    fun passingConfigurationKeepsSchemaEndpointAndCredentialBlockersInTheWorklist() {
        val original = fixture(3, 3)
        val failures = listOf(
            AppAuditObservation(AppAuditOutcome.BLOCKED, "MCP_DISCOVERY_SCHEMA_INVALID", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, observationSource = AppAuditObservationSource.CACHE, httpStatus = 200, validationRule = "PROTOCOL_VERSIONS_ARRAY_REQUIRED"),
            AppAuditObservation(AppAuditOutcome.BLOCKED, "MCP_DISCOVERY_CONFIGURATION", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, observationSource = AppAuditObservationSource.CACHE, httpStatus = 404, validationRule = "DISCOVERY_REQUEST_ACCEPTANCE_REQUIRED"),
            AppAuditObservation(AppAuditOutcome.FAIL, "MCP_LOCAL_CREDENTIAL_INVALID", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, validationRule = "CREDENTIAL_REAUTHORIZATION_REQUIRED")
        )
        val run = original.copy(
            results = original.results.map { result ->
                result.entryId.removePrefix("discovery-").toIntOrNull()?.let { result.copy(observation = failures[it]) } ?: result
            }
        )
        val checks = qualification(run)["checks"]!!.jsonArray.take(3).map { it.jsonObject }
        checks.forEachIndexed { index, check ->
            val discoveryId = "check-${index * 3 + 2}"
            assertEquals("READY", check["configurationReadiness"]!!.jsonPrimitive.content)
            assertEquals(discoveryId, check["discoveryCheckId"]!!.jsonPrimitive.content)
            assertEquals(listOf(discoveryId), check["blockingCheckIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
        assertEquals(listOf("DISCOVERY_RECHECK_REQUIRED", "DISCOVERY_RECHECK_REQUIRED", "CREDENTIAL_REAUTHORIZATION_REQUIRED"), checks.map { it["readiness"]!!.jsonPrimitive.content })
        assertEquals("REAUTHORIZE_SAVED_CONNECTION_THEN_RECHECK", checks[2]["nextAction"]!!.jsonPrimitive.content)
        assertEquals("REAUTHORIZATION_REQUIRED", checks[2]["credentialReadiness"]!!.jsonPrimitive.content)
        assertEquals(original.plan, run.plan)
    }

    @Test
    fun cachedOrUnboundMetadataDoesNotMarkDiscoveryReadyForExecution() {
        val original = fixture(1, 1)
        val observation = original.results.single { it.entryId == "discovery-0" }.observation
        val cases = listOf(
            observation.copy(observationSource = AppAuditObservationSource.CACHE) to "FRESH_CHECK_REQUIRED",
            observation.copy(configurationRevision = null) to "OBSERVATION_IDENTITY_REQUIRED",
            observation.copy(observedAtEpochMs = null) to "OBSERVATION_IDENTITY_REQUIRED",
            observation.copy(dispatchEvidence = AppAuditDispatchEvidence.UNKNOWN) to "CHECK_REQUIRED"
        )
        cases.forEach { (replacement, expected) ->
            val run = original.copy(results = original.results.map { if (it.entryId == "discovery-0") it.copy(observation = replacement) else it })
            val check = qualification(run)["checks"]!!.jsonArray.first().jsonObject
            assertEquals(expected, check["discoveryReadiness"]!!.jsonPrimitive.content)
            assertEquals("DISCOVERY_RECHECK_REQUIRED", check["readiness"]!!.jsonPrimitive.content)
            assertEquals(listOf("check-2"), check["blockingCheckIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test
    fun emptyCatalogueRequiresReviewingToolExposureAndKeepsMetadataEvidence() {
        val original = fixture(1, 1)
        val run = original.copy(
            results = original.results.map {
                if (it.entryId != "discovery-0") {
                    it
                } else {
                    it.copy(
                        observation = it.observation.copy(reasonCode = "MCP_EMPTY_CATALOG", metrics = listOf(AppAuditMetric(AppAuditMetricName.TOOL_COUNT, 0.0)))
                    )
                }
            }
        )
        val check = qualification(run)["checks"]!!.jsonArray.first().jsonObject
        assertEquals("VERIFIED", check["discoveryReadiness"]!!.jsonPrimitive.content)
        assertEquals("NO_TOOLS_ADVERTISED", check["readiness"]!!.jsonPrimitive.content)
        assertEquals("0", check["defaultMaxToolCalls"]!!.jsonPrimitive.content)
        assertEquals("CHECK_TOOL_EXPOSURE_OR_EXCLUDE_RESOURCE_ONLY_CONNECTION", check["nextAction"]!!.jsonPrimitive.content)
    }

    @Test
    fun selectedGenerationAndDeviceMeasurementKeepExplicitSeparateRequirements() {
        val original = fixture(0, 0)
        val revision = "a".repeat(64)
        val configuration = AppAuditPlanEntry(
            "model-config",
            AppAuditSelectedModelProbeSource.SOURCE_ID,
            AppAuditSelectedModelProbeSource.CONFIGURATION,
            "selected_model",
            "Selected model configuration",
            targetId = "model:private",
            sourceAreaIds = listOf("models_and_profiles", "local_inference"),
            configurationRevision = revision
        )
        val generation = configuration.copy(id = "model-generation", probeId = AppAuditSelectedModelProbeSource.GENERATION, evidenceKind = AppAuditEvidenceKind.MODEL_EXECUTION, safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY, prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED)
        val device = AppAuditPlanEntry("device", "source-inventory", "device-performance", "android_shell", "Device measurements", sourceAreaIds = listOf("android_shell", "chat_ui"), evidenceKind = AppAuditEvidenceKind.DEVICE_MEASUREMENT, safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY, prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED)
        val result = AppAuditResult(configuration.id, AppAuditObservation(AppAuditOutcome.PASS, "MODEL_CONFIGURATION_ONLY", observationSource = AppAuditObservationSource.CONFIGURATION, configurationRevision = revision), 1, 2, 1, true)
        val run = original.copy(plan = listOf(configuration, generation, device), results = listOf(result))
        val checks = qualification(run)["checks"]!!.jsonArray.map { it.jsonObject }
        val model = checks.first()
        assertEquals("MODEL_BENCHMARK_REQUIRED", model["readiness"]!!.jsonPrimitive.content)
        assertEquals("PREPARE_EXACT_SELECTED_PROFILE_BENCHMARK", model["nextAction"]!!.jsonPrimitive.content)
        val requirements = model["requirements"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(requirements.containsAll(listOf("MODEL_BENCHMARK", "DEVICE_AND_APK_IDENTITY", "NATIVE_RUNTIME_MEASUREMENT")))
        assertFalse("REMOTE_SERVICE_OBSERVATION" in requirements)
        assertEquals(listOf("scripts/qualify_device_performance.py"), checks[1]["namedHarnesses"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("FRAME_TIMING_AND_MEMORY" in checks[1]["requirements"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, appAuditSummary(run).verified)
    }

    private fun qualification(run: AppAuditRun): JsonObject = appAuditQualificationPlan(run, run.plan.mapIndexed { index, entry -> entry.id to "check-${index + 1}" }.toMap(), appAuditTargetAliases(run.plan), true)

    private fun fixture(targetCount: Int = 39, configuredCount: Int = 25): AppAuditRun {
        val plan = buildList {
            repeat(targetCount) { index ->
                val configured = index < configuredCount
                val revision = appAuditHash("configuration-$index").takeIf { configured }
                val configuration = AppAuditPlanEntry(
                    "configuration-$index", "plugins", AppAuditPluginProbeSource.CONFIGURATION, "mcp_connection", "Plugin configuration",
                    targetId = "connection:private-$index", sourceAreaIds = listOf("mcp_plugins"),
                    prerequisite = if (configured) AppAuditPrerequisite.READY else AppAuditPrerequisite.NOT_CONFIGURED,
                    configFingerprint = appAuditHash("target-$index"), configurationRevision = revision
                )
                add(configuration)
                add(configuration.copy(id = "discovery-$index", probeId = AppAuditPluginProbeSource.MCP_DISCOVERY, label = "MCP discovery", evidenceKind = AppAuditEvidenceKind.LIVE_METADATA, safetyClass = AppAuditSafetyClass.NETWORK_READ))
                add(configuration.copy(id = "execution-$index", probeId = AppAuditPluginProbeSource.TOOL_EXECUTION, label = "Plugin tool execution", evidenceKind = AppAuditEvidenceKind.TOOL_EXECUTION, safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY, prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED))
            }
            listOf("android_shell", "chat_ui", "model_providers", "network", "persistence", "voice_and_media", "portable_domain", "gateway_software", "authentication_service", "other_production").forEach { area ->
                add(AppAuditPlanEntry("source-area:$area", "source-inventory", "external-coverage", area, "External coverage", sourceAreaIds = listOf(area), evidenceKind = AppAuditEvidenceKind.EXTERNAL_SUITE, safetyClass = AppAuditSafetyClass.EXTERNAL_ONLY, prerequisite = AppAuditPrerequisite.EXTERNAL_HARNESS_REQUIRED))
            }
        }
        val results = plan.filter { it.probeId in setOf(AppAuditPluginProbeSource.CONFIGURATION, AppAuditPluginProbeSource.MCP_DISCOVERY) && it.prerequisite == AppAuditPrerequisite.READY }.map { entry ->
            val discovery = entry.probeId == AppAuditPluginProbeSource.MCP_DISCOVERY
            AppAuditResult(entry.id, AppAuditObservation(AppAuditOutcome.PASS, if (discovery) "MCP_DISCOVERY_VERIFIED" else "PLUGIN_CONFIGURATION_ONLY", evidenceKind = entry.evidenceKind, observationSource = if (discovery) AppAuditObservationSource.LIVE else AppAuditObservationSource.CONFIGURATION, dispatchEvidence = if (discovery) AppAuditDispatchEvidence.CONFIRMED else AppAuditDispatchEvidence.NOT_DISPATCHED, configurationRevision = entry.configurationRevision, observedAtEpochMs = 2), 1, 2, 1, true)
        }
        return AppAuditRun("qualification", 1, "a".repeat(64), "b".repeat(40), "0.9.66.6", "debug", "c".repeat(64), 1, 1, plan, results = results)
    }
}
