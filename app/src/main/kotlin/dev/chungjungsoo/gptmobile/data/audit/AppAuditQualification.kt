package dev.chungjungsoo.gptmobile.data.audit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A reviewable worklist, never a capability grant, execution receipt, or score import. */
internal fun appAuditQualificationPlan(
    run: AppAuditRun,
    checkIds: Map<String, String>,
    targetAliases: Map<String, String>,
    completeEvidence: Boolean
): JsonObject {
    val buildBound = run.sourceCommit.matches(Regex("[a-fA-F0-9]{40}")) &&
        run.buildVersion.matches(Regex("[0-9]{1,5}(\\.[0-9]{1,5}){1,4}")) &&
        run.buildVariant in setOf("debug", "release", "benchmark") &&
        listOf(run.sourceInventoryDigest, run.catalogHash).all { it.matches(Regex("[a-f0-9]{64}")) }
    val results = run.results.distinctBy { it.entryId }.associateBy { it.entryId }
    val gaps = run.plan.distinctBy { it.id }.filter { entry ->
        entry.evidenceKind in QUALIFICATION_EVIDENCE_KINDS &&
            appAuditScore(run.copy(plan = listOf(entry), results = listOfNotNull(results[entry.id]))).passedWeight == 0
    }
    val runBinding = if (buildBound) {
        appAuditHash("app-audit-qualification-v1\n${run.id}\n${run.sourceCommit}\n${run.buildVersion}\n${run.buildVariant}\n${run.sourceInventoryDigest}\n${run.catalogHash}")
    } else {
        null
    }
    return buildJsonObject {
        put("schemaVersion", 1)
        put("authority", "PLAN_ONLY")
        put("grantsExecution", false)
        put("importsPromoteAuditScore", false)
        put("completeRetainedEvidence", completeEvidence)
        put("runBinding", runBinding)
        put("pendingChecks", gaps.size)
        put("pendingChecksScope", "RETAINED_PLAN")
        put("scope", "Named target fixtures and external suites require independent execution evidence. This worklist does not execute tools, change retained results, establish service identity, or claim line coverage.")
        put("acceptance", "Review evidence from the authorized harness against the exact app build, source inventory, accepted plan and configuration binding. Reject stale, mismatched, untrusted, incomplete or uncertain receipts. Discovery alone cannot satisfy tool execution.")
        put(
            "requirements",
            JsonArray(
                QUALIFICATION_REQUIREMENTS.map { (id, instruction) ->
                    buildJsonObject {
                        put("id", id)
                        put("instruction", instruction)
                    }
                }
            )
        )
        put(
            "checks",
            JsonArray(
                gaps.map { entry ->
                    val target = entry.targetId
                    val configuration = run.plan.singleOrNull {
                        it.sourceId == AppAuditPluginProbeSource.SOURCE_ID && it.probeId == AppAuditPluginProbeSource.CONFIGURATION && it.targetId == target
                    }.takeIf { target != null }
                    val revision = safeAuditConfigurationRevision(entry.configurationRevision)
                    val family = qualificationFamily(entry)
                    val readiness = when {
                        !buildBound -> "BUILD_IDENTITY_UNAVAILABLE"
                        configuration?.prerequisite in setOf(AppAuditPrerequisite.NOT_CONFIGURED, AppAuditPrerequisite.UNSUPPORTED) -> "CONFIGURATION_REQUIRED"
                        configuration?.prerequisite == AppAuditPrerequisite.PERMISSION_REQUIRED -> "ENABLE_PERMISSION_REQUIRED"
                        entry.evidenceKind == AppAuditEvidenceKind.TOOL_EXECUTION && revision == null -> "CONFIGURATION_IDENTITY_UNAVAILABLE"
                        entry.evidenceKind == AppAuditEvidenceKind.TOOL_EXECUTION && (configuration == null || results[configuration.id]?.outcome != AppAuditOutcome.PASS || results[configuration.id]?.observation?.configChanged == true) -> "CONFIGURATION_CHECK_REQUIRED"
                        entry.evidenceKind == AppAuditEvidenceKind.TOOL_EXECUTION -> "TARGET_FIXTURE_REQUIRED"
                        entry.evidenceKind == AppAuditEvidenceKind.MODEL_EXECUTION -> "MODEL_BENCHMARK_REQUIRED"
                        else -> "EXTERNAL_HARNESS_REQUIRED"
                    }
                    val binding = runBinding?.takeIf { entry.evidenceKind != AppAuditEvidenceKind.TOOL_EXECUTION || revision != null }?.let {
                        appAuditHash("$it\n${entry.id}\n${entry.evidenceKind}\n${entry.configFingerprint}\n$revision\n${entry.fixtureHash}")
                    }
                    buildJsonObject {
                        put("checkId", checkIds[entry.id]?.takeIf { it.matches(Regex("check-[1-9][0-9]*")) })
                        put("targetAlias", target?.let(targetAliases::get)?.takeIf { it.matches(Regex("target-[1-9][0-9]*")) })
                        put("sourceAreaIds", JsonArray(entry.sourceAreaIds.take(64).filter { id -> AppAuditSourceInventory.areas.any { it.id == id } }.distinct().map(::JsonPrimitive)))
                        put("requiredEvidenceKind", entry.evidenceKind.name)
                        put("family", family)
                        put("readiness", readiness)
                        put("qualificationBinding", binding)
                        put("configurationRevision", revision)
                        put("remoteServiceIdentity", if (entry.sourceAreaIds.any { it in REMOTE_SOURCE_AREAS }) "NOT_OBSERVED_BY_THIS_AUDIT" else "NOT_APPLICABLE_TO_THIS_PLAN")
                        put("configurationCheckId", configuration?.id?.let(checkIds::get)?.takeIf { it.matches(Regex("check-[1-9][0-9]*")) })
                        put("configurationPrerequisite", configuration?.prerequisite?.name)
                        put("currentOutcome", results[entry.id]?.outcome?.name ?: "PENDING")
                        put("defaultMaxToolCalls", if (entry.evidenceKind == AppAuditEvidenceKind.TOOL_EXECUTION) JsonPrimitive(1) else JsonNull)
                        put("perFixtureTimeoutMs", run.limits.perProbeTimeoutMs)
                        put("requirements", JsonArray(qualificationRequirements(entry, family).map(::JsonPrimitive)))
                    }
                }
            )
        )
    }
}

private fun qualificationFamily(entry: AppAuditPlanEntry): String = when (entry.evidenceKind) {
    AppAuditEvidenceKind.TOOL_EXECUTION -> "TARGET_TOOL_EXECUTION"
    AppAuditEvidenceKind.MODEL_EXECUTION -> "MODEL_GENERATION_BENCHMARK"
    AppAuditEvidenceKind.DEVICE_MEASUREMENT -> "DEVICE_MEASUREMENT_SUITE"
    else -> when (entry.sourceAreaIds.singleOrNull()) {
        "android_shell" -> "ANDROID_LIFECYCLE_SUITE"
        "chat_ui", "accessibility_and_localization" -> "DEVICE_RENDERING_ACCESSIBILITY_SUITE"
        "persistence" -> "DATABASE_MIGRATION_RECOVERY_SUITE"
        "voice_and_media" -> "VOICE_MEDIA_PERMISSION_SUITE"
        "local_inference", "native_bridge" -> "NATIVE_RUNTIME_DEVICE_SUITE"
        "portable_domain" -> "PORTABLE_DOMAIN_SUITE"
        "model_providers" -> "PROVIDER_STREAMING_CONTRACT_SUITE"
        "network" -> "NETWORK_TRANSPORT_RECOVERY_SUITE"
        "other_production" -> "RESIDUAL_SOURCE_BEHAVIOR_SUITE"
        "gateway_software" -> "GATEWAY_SERVICE_SUITE"
        "authentication_service" -> "AUTHENTICATION_SERVICE_SUITE"
        "external_integrations" -> "EXTERNAL_INTEGRATION_SUITE"
        else -> "EXTERNAL_SOURCE_SUITE"
    }
}

private fun qualificationRequirements(entry: AppAuditPlanEntry, family: String): List<String> = buildList {
    addAll(listOf("EXACT_BUILD_AND_PLAN", "EXPLICIT_AUTHORIZATION", "BOUNDED_FIXTURE", "EXPECTED_ASSERTIONS", "DATED_HARNESS_ARTIFACT", "SAFE_RESULT_SUMMARY", "SOURCE_COVERAGE_LIMIT"))
    when (entry.evidenceKind) {
        AppAuditEvidenceKind.TOOL_EXECUTION -> {
            addAll(listOf("EXACT_CONFIGURATION", "NAMED_TOOL_AND_SCHEMA", "READ_ONLY_OR_OWNED_FIXTURE", "DISPATCH_RECEIPT", "VALIDATION_AND_DENIAL", "CANCELLATION_AND_RECOVERY", "OWNED_FIXTURE_CLEANUP"))
            if (entry.sourceAreaIds.any { it in REMOTE_SOURCE_AREAS }) add("REMOTE_SERVICE_OBSERVATION")
        }
        AppAuditEvidenceKind.MODEL_EXECUTION -> addAll(listOf("EXACT_CONFIGURATION", "MODEL_BENCHMARK", "REMOTE_SERVICE_OBSERVATION"))
        else -> {
            add("CANCELLATION_AND_RECOVERY")
            when (family) {
                "ANDROID_LIFECYCLE_SUITE" -> addAll(listOf("DEVICE_AND_APK_IDENTITY", "ANDROID_LIFECYCLE"))
                "DEVICE_RENDERING_ACCESSIBILITY_SUITE" -> addAll(listOf("DEVICE_AND_APK_IDENTITY", "RENDERING_AND_ACCESSIBILITY"))
                "DATABASE_MIGRATION_RECOVERY_SUITE" -> add("DATABASE_RECOVERY")
                "VOICE_MEDIA_PERMISSION_SUITE" -> addAll(listOf("DEVICE_AND_APK_IDENTITY", "MEDIA_PERMISSIONS"))
                "NATIVE_RUNTIME_DEVICE_SUITE", "DEVICE_MEASUREMENT_SUITE" -> addAll(listOf("DEVICE_AND_APK_IDENTITY", "NATIVE_RUNTIME_MEASUREMENT"))
                "GATEWAY_SERVICE_SUITE" -> addAll(listOf("SERVICE_BUILD_IDENTITY", "REMOTE_SERVICE_OBSERVATION", "NETWORK_PROTOCOL_AUTHORIZATION", "MODEL_BENCHMARK"))
                "AUTHENTICATION_SERVICE_SUITE", "EXTERNAL_INTEGRATION_SUITE" -> addAll(listOf("SERVICE_BUILD_IDENTITY", "REMOTE_SERVICE_OBSERVATION", "NETWORK_PROTOCOL_AUTHORIZATION"))
                "PROVIDER_STREAMING_CONTRACT_SUITE" -> addAll(listOf("PROVIDER_STREAMING_CONTRACT", "MODEL_BENCHMARK", "REMOTE_SERVICE_OBSERVATION"))
                "NETWORK_TRANSPORT_RECOVERY_SUITE" -> add("NETWORK_RECOVERY")
                "RESIDUAL_SOURCE_BEHAVIOR_SUITE" -> add("RESIDUAL_SOURCE_CASES")
                else -> add("NAMED_SOURCE_SUITE")
            }
        }
    }
}

private val REMOTE_SOURCE_AREAS = setOf("mcp_plugins", "marketplace_plugins", "gateway_client", "model_providers", "gateway_software", "authentication_service", "external_integrations")
private val QUALIFICATION_EVIDENCE_KINDS = setOf(AppAuditEvidenceKind.TOOL_EXECUTION, AppAuditEvidenceKind.MODEL_EXECUTION, AppAuditEvidenceKind.DEVICE_MEASUREMENT, AppAuditEvidenceKind.EXTERNAL_SUITE)
private val QUALIFICATION_REQUIREMENTS = linkedMapOf(
    "EXACT_BUILD_AND_PLAN" to "Record the app commit, version, variant, source inventory digest, catalog hash, run binding and check qualification binding exactly as exported. Do not reuse evidence from another build or accepted plan.",
    "EXPLICIT_AUTHORIZATION" to "Select the intended saved target and approve the named fixture or harness scope. This manifest grants no tool execution or mutation authority.",
    "BOUNDED_FIXTURE" to "Declare a deadline and call budget before execution. The target smoke default is one tool call; negative cases and owned cleanup require separately declared bounds. Do not replay an uncertain mutation.",
    "EXPECTED_ASSERTIONS" to "Specify inputs and expected success and failure assertions before running the fixture. Record assertion counts and outcomes; a nonempty response alone is not a pass.",
    "DATED_HARNESS_ARTIFACT" to "Retain the observation time, harness name and version, exact test or fixture identifier, fixture digest, reviewed report location and artifact digest. An untrusted self-declared pass is not execution evidence.",
    "SAFE_RESULT_SUMMARY" to "Keep typed error codes, dispatch evidence, status, durations and bounded assertion counts. Omit credentials, private identifiers, raw tool inputs, responses and unrelated application records.",
    "SOURCE_COVERAGE_LIMIT" to "Associate evidence only with the executed tests, fixture and named source area. Report line or branch coverage only when actual instrumentation and a matching coverage report exist.",
    "EXACT_CONFIGURATION" to "Verify the saved target and its opaque configuration revision before and after the check. A missing or changed revision cannot qualify the frozen target. Never substitute a similar name, endpoint or connection.",
    "NAMED_TOOL_AND_SCHEMA" to "Choose an explicitly approved tool and record its exact name or safe local mapping, input-schema digest and fixture identity in the authorized harness. Listing a catalogue does not execute a tool or prove all tools work.",
    "READ_ONLY_OR_OWNED_FIXTURE" to "Use an approved read-only fixture by default. A mutating tool requires a separately authorized disposable resource owned by the test and a reviewed cleanup plan. Do not select arbitrary advertised tools automatically.",
    "DISPATCH_RECEIPT" to "Record a matching invocation receipt and its terminal outcome. Separate local rejection, confirmed dispatch and unknown dispatch. HTTP or discovery metadata alone cannot verify the tool result.",
    "VALIDATION_AND_DENIAL" to "In an isolated harness, verify malformed input and denied authority produce the expected fixed error and zero dispatched calls. Verify the approved positive fixture separately.",
    "CANCELLATION_AND_RECOVERY" to "Exercise bounded timeout or cancellation and recovery in the appropriate isolated harness. Retain uncertain outcomes for reconciliation; verify no duplicate mutation or automatic replay after interruption.",
    "OWNED_FIXTURE_CLEANUP" to "For an authorized mutating fixture, retain ownership proof and verify cleanup or restoration with a matching receipt. Mark cleanup not applicable only for a proven read-only fixture.",
    "MODEL_BENCHMARK" to "Use the existing model benchmark queue with the exact selected profile and configuration. Record requested and independently observed profile/model identity, first-output timing, useful final text assertions, finalization/finish reason and unavailable-selected-model behavior. An unavailable model must not silently substitute another. Keep generation evidence separate from discovery and acceleration claims.",
    "DEVICE_AND_APK_IDENTITY" to "Install the exact APK and record its digest, app build, OS/API, ABI and relevant device or emulator capabilities. Do not infer a physical device result from host unit tests.",
    "ANDROID_LIFECYCLE" to "Exercise foreground/background transitions, process interruption and restoration on the exact APK, checking durable state, cancellation and duplicate-work prevention.",
    "RENDERING_AND_ACCESSIBILITY" to "Run the named Compose/device interactions, rendering and accessibility checks, including focus/IME transitions, semantics and relevant screen configurations.",
    "DATABASE_RECOVERY" to "Run supported schema migrations and isolated interrupted-write or process-recovery fixtures, checking data integrity and bounded recovery without the user's live database.",
    "MEDIA_PERMISSIONS" to "Test microphone/media permission grant, denial and revocation plus lifecycle cancellation using controlled fixtures on the intended device.",
    "NATIVE_RUNTIME_MEASUREMENT" to "Measure the named native/runtime behavior on supported hardware with the exact binary, model/backend and limits. Record observed backend use, failures and resource measurements.",
    "SERVICE_BUILD_IDENTITY" to "For a controlled gateway, authentication broker or integration deployment, record its exact deployed commit or artifact digest, version, instance provenance and relevant dependency/configuration identity. The Android app commit does not establish remote source identity. If a third party does not disclose a build identity, mark it unavailable and limit source-version claims without discarding independently observed functional evidence.",
    "REMOTE_SERVICE_OBSERVATION" to "Record available independently observed service/protocol version, instance provenance and verification method; separate declared from verified identity. Mark undisclosed remote commit/build identity unavailable. Do not infer it from the Android commit or tool catalogue, and do not fabricate an identity to recognize an otherwise supported functional result.",
    "PROVIDER_STREAMING_CONTRACT" to "Run named provider adapter cases for stream/SSE framing, split UTF-8, reasoning versus visible text, tool envelopes, usage/finalization, cancellation and structured provider failures. Include requested-versus-observed model and unavailable-selected-model behavior.",
    "NETWORK_RECOVERY" to "Run bounded DNS, route/refusal, TLS, authentication, timeout, cancellation and retry/recovery cases. Verify status/phase classification, physical request bounds, conservative unknown dispatch and no mutation replay.",
    "RESIDUAL_SOURCE_CASES" to "Enumerate the residual sourceFiles in this area from the matching inventory and assign named behavioral cases to each meaningful subsystem. Report exclusions explicitly; a generic suite name or inventory count cannot establish coverage.",
    "NETWORK_PROTOCOL_AUTHORIZATION" to "Run the named service protocol, authentication, authorization and recovery cases against an authorized isolated environment. Use owned fixtures for changes and verify cleanup.",
    "NAMED_SOURCE_SUITE" to "Identify a concrete suite and assertions for this source area, run it on the exact source/build under its required environment, and retain the bounded test report. Source inventory alone is not execution."
)
