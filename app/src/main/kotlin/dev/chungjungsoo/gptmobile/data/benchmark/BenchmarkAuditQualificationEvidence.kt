package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.audit.AppAuditRun
import dev.chungjungsoo.gptmobile.data.audit.matches
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Joins retained observations without editing the originating audit or trusting a self-declared pass. */
internal fun benchmarkAuditQualificationEvidence(run: BenchmarkRun, batch: BenchmarkBatch, item: BenchmarkQueueItem, audit: AppAuditRun?): JsonObject? {
    val binding = item.auditModelBinding ?: return null
    val bound = binding.matches(audit, item.profile) &&
        item.runId == run.id &&
        run.batchId == batch.id &&
        run.queueOrdinal == item.ordinal &&
        run.profileUid == item.profile.uid &&
        run.model == item.profile.model &&
        run.provider == item.profile.compatibleType.name &&
        run.configKey == item.configKey &&
        run.appCommit == audit?.sourceCommit &&
        run.local == (item.profile.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM)
    val manifest = run.caseManifest.orEmpty()
    val manifestValid = manifest.isNotEmpty() &&
        manifest.map { it.id }.distinct().size == manifest.size &&
        run.samples.map { it.testId }.distinct().size == run.samples.size &&
        run.plannedTrials == manifest.size
    val cases = manifest.map { test ->
        val sample = run.samples.singleOrNull { it.testId == test.id }
        val receipt = sample?.auditModelConfiguration
        val configurationChecked = receipt != null &&
            receipt.configurationRevision == binding.configurationRevision &&
            receipt.beforeGenerationVerified &&
            receipt.afterGenerationVerified &&
            receipt.admittedRounds > 0 &&
            receipt.completedRounds == receipt.admittedRounds &&
            receipt.failureCode == null &&
            receipt.observedAtEpochMs >= run.startedAt
        val fixtureMatched = sample != null &&
            sample.fixtureContentHash == test.contentHash &&
            sample.validatorVersion == test.validatorVersion &&
            sample.validation?.validatorVersion == test.validatorVersion
        val outputObserved = sample != null && sample.outputCharacters > 0 && (sample.answerLength ?: sample.outputCharacters) > 0
        val cleanup = if (run.local) sample?.runtimeCleanup?.safeForSuccessor == true else true
        val passed = bound &&
            configurationChecked &&
            fixtureMatched &&
            sample?.outcome == BenchmarkOutcome.PASSED &&
            sample.validation?.gradePassed == true &&
            sample.requestDispatched == true &&
            sample.terminalReceived == true &&
            !sample.finishReason.isNullOrBlank() &&
            outputObserved &&
            cleanup
        passed to buildJsonObject {
            put("caseId", test.id)
            put("fixtureContentHash", test.contentHash)
            put("fixtureMatched", fixtureMatched)
            put("validatorVersion", test.validatorVersion)
            put("assertionGradePassed", sample?.validation?.gradePassed?.let(::JsonPrimitive) ?: JsonNull)
            put("outcome", sample?.outcome?.name ?: "NOT_EXECUTED")
            put("configurationCheckedBeforeAndAfter", configurationChecked)
            put("requestDispatched", sample?.requestDispatched?.let(::JsonPrimitive) ?: JsonNull)
            put("requestAdmitted", sample?.requestAdmitted?.let(::JsonPrimitive) ?: JsonNull)
            put("physicalRequestCount", sample?.physicalRequestIds?.distinct()?.size ?: 0)
            put("physicalRequestMeaning", "Recorded request admission IDs; these do not independently prove that bytes reached the provider.")
            put("terminalReceived", sample?.terminalReceived?.let(::JsonPrimitive) ?: JsonNull)
            put("finishReason", sample?.finishReason?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,80}")) })
            put("firstVisibleOutputMs", sample?.firstTextMs?.takeIf { it >= 0 }?.let(::JsonPrimitive) ?: JsonNull)
            put("nonemptyOutputObserved", outputObserved)
            put(
                "localRuntimeCleanup",
                if (!run.local) {
                    "NOT_APPLICABLE_REMOTE_PROFILE"
                } else if (cleanup) {
                    "CONFIRMED"
                } else {
                    "NOT_CONFIRMED"
                }
            )
            put("failureCode", sample?.failureCategory?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) })
            put("functionalAssertionsSatisfied", passed)
        }
    }
    val complete = run.finished &&
        item.terminal &&
        item.state == BenchmarkQueueState.COMPLETED &&
        run.samples.size == manifest.size &&
        run.samples.all { sample -> manifest.any { it.id == sample.testId } }
    return buildJsonObject {
        put("schemaVersion", 1)
        put("authority", "RECORDED_BENCHMARK_EVIDENCE_REQUIRES_REVIEW")
        put("importsPromoteAuditScore", false)
        put("runId", run.id)
        put("auditRunBinding", binding.runBinding)
        put("qualificationBinding", binding.qualificationBinding)
        put("configurationRevision", binding.configurationRevision)
        put("bindingMatchesRetainedAuditAndRun", bound)
        put("declaredProfileUid", item.profile.uid)
        put("declaredProvider", item.profile.compatibleType.name)
        put("requestedModel", item.profile.model)
        put("independentlyObservedModelIdentity", "NOT_ESTABLISHED_BY_THIS_JOIN")
        put("modelIdentityScope", "The frozen profile identifies the requested model. Configuration checks, text, and terminal events do not independently establish which model a remote provider executed. Available native execution receipts remain separate.")
        put("executionPolicy", run.executionPolicy?.name)
        put("fixtureManifestHash", run.fixtureManifestHash)
        put("startedAtEpochMs", run.startedAt)
        put("endedAtEpochMs", run.endedAt?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "functionalStatus",
            when {
                !bound -> "BINDING_MISMATCH_OR_UNAVAILABLE"
                !manifestValid -> "FIXTURE_MANIFEST_UNAVAILABLE_OR_INVALID"
                !complete -> "EXECUTION_INCOMPLETE_OR_BLOCKED"
                cases.all { it.first } -> "FUNCTIONAL_ASSERTIONS_PASSED"
                else -> "FUNCTIONAL_ASSERTIONS_NOT_SATISFIED"
            }
        )
        put("qualificationStatus", "INDEPENDENT_IDENTITY_AND_BEHAVIOR_REVIEW_REQUIRED")
        put("unavailableSelectedModelBehavior", "NOT_ESTABLISHED_BY_SUCCESSFUL_GENERATION")
        put("externalMutationCleanup", "NOT_APPLICABLE_SYNTHETIC_PROMPTS_AND_IN_MEMORY_TOOLS")
        put("scope", "Only these bound generation fixtures are assessed. This is not device qualification, external tool execution, provider-source verification, or a pass imported into the historical audit.")
        put("cases", JsonArray(cases.map { it.second }))
    }
}
