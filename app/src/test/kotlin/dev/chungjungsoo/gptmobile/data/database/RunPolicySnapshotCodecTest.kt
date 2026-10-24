package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunPolicyRef
import dev.chungjungsoo.gptmobile.data.research.researchHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Intentionally formatted JSON: serializers must not change its sealed bytes during persistence. */
internal object RunPolicyFixture {
    val task = "Compare the original evidence, including escaped \"quotes\", café and / paths. " + "Keep the accepted source scope. ".repeat(100)
    fun raw(profile: String = "a", task: String = this.task, revision: String = "accepted-revision", requestedVersion: Int = 1): String {
        val requested = """{ "version":$requestedVersion, "participantUids":["a","b"], "requestedModels":{"a":"model","b":"model"}, "userPrompt":${Json.encodeToString(task)}, "requestedPolicyDigest":"$revision", "settings":{"depth":50} }"""
        val plan = """{ "version":1, "requested":$requested, "baselineByOwner":{}, "finalizationReserve":{}, "optionalAllowance":{}, "effectivePlanDigest":"frozen-plan", "workItems":[] }"""
        val context = """{ "requested":$requested, "plan":$plan, "rootBudgetId":"1:10", "profileUid":"$profile", "chatId":1 }"""
        return """{
            "version":1, "policyRecordKind":"COMBINED_ACCEPTED_TURN",
            "context":$context, "tools":{"combined":$context},
            "futureField": { "text":"literal requested and plan names are not policy nodes" }
        }"""
    }
    fun run(id: String = "run-a", profile: String = "a", chatId: Int = 1, userId: Int = 10, assistantId: Int = 20, raw: String = raw(profile), sealed: Boolean = true): AgentRun =
        AgentRun(id, chatId, userId, assistantId, profile, "CUSTOM", "model", synthesisSnapshot = raw, synthesisKey = researchHash(raw).takeIf { sealed })
}

class RunPolicySnapshotCodecTest {
    @Test fun repeatedPoliciesAndNestedPlansUseOneRecordAndReconstructByteExactSeal() {
        val originals = (1..9).map { RunPolicyFixture.run(id = "run-$it", profile = if (it % 2 == 0) "a" else "b") }
        val normalized = originals.map { requireNotNull(RunPolicySnapshotCodec.normalize(it)) }
        val canonical = normalized.flatMap { it.policies }.distinct()
        assertEquals(2, canonical.size)
        assertEquals(setOf("REQUESTED_POLICY", "WORK_PLAN"), canonical.map { it.kind }.toSet())
        normalized.zip(originals).forEach { (stored, original) ->
            val refs = stored.policies.map { AgentRunPolicyRef(original.runId, original.chatId, it.digest) }
            val hydrated = RunPolicySnapshotCodec.hydrate(original.copy(synthesisSnapshot = stored.snapshot), canonical, refs)
            assertEquals(original, hydrated)
            assertEquals(original.synthesisKey, researchHash(requireNotNull(hydrated.synthesisSnapshot)))
            assertNotNull(CombinedAcceptedTurn.decode(hydrated.synthesisSnapshot))
        }
        val storedCharacters = canonical.sumOf { it.payload.length } + normalized.sumOf { it.snapshot.length }
        assertTrue("Shared policy storage should eliminate the repeated large task body", storedCharacters < originals.sumOf { it.synthesisSnapshot!!.length } / 2)
        assertFalse(canonical.single { it.kind == "WORK_PLAN" }.payload.contains("Keep the accepted source scope"))
    }

    @Test fun missingOrTamperedPolicyNeverUsesCacheOrInventsCurrentSettings() {
        val original = RunPolicyFixture.run()
        val saved = requireNotNull(RunPolicySnapshotCodec.normalize(original))
        val run = original.copy(synthesisSnapshot = saved.snapshot)
        val refs = saved.policies.map { AgentRunPolicyRef(run.runId, run.chatId, it.digest) }
        assertEquals(original, RunPolicySnapshotCodec.hydrate(run, saved.policies, refs))
        assertPolicyFailure { RunPolicySnapshotCodec.hydrate(run, emptyList(), refs) }
        assertPolicyFailure { RunPolicySnapshotCodec.hydrate(run, saved.policies, emptyList()) }
        val changedAuthority = saved.policies.map { it.copy(authorityDigest = "different-grant") }
        assertPolicyFailure { RunPolicySnapshotCodec.hydrate(run, changedAuthority, refs) }
        val changedBody = saved.policies.map { it.copy(payload = it.payload.replace("Keep the accepted source scope", "Adopt current settings instead")) }
        assertPolicyFailure { RunPolicySnapshotCodec.hydrate(run, changedBody, refs) }
        assertPolicyFailure { RunPolicySnapshotCodec.hydrate(run.copy(synthesisKey = "wrong-seal"), saved.policies, refs) }
        assertPolicyFailure { RunPolicySnapshotCodec.hydrate(run.copy(synthesisSnapshot = "{}"), saved.policies, refs) }
    }

    @Test fun referenceCannotMoveToAnotherChatTaskOrRun() {
        val original = RunPolicyFixture.run()
        val saved = requireNotNull(RunPolicySnapshotCodec.normalize(original))
        val stored = original.copy(synthesisSnapshot = saved.snapshot)
        val refs = saved.policies.map { AgentRunPolicyRef(stored.runId, stored.chatId, it.digest) }
        assertPolicyFailure { RunPolicySnapshotCodec.hydrate(stored.copy(userMessageId = 11), saved.policies, refs) }
        assertPolicyFailure { RunPolicySnapshotCodec.hydrate(stored.copy(runId = "different"), saved.policies, refs.map { it.copy(runId = "different") }) }
        assertPolicyFailure { RunPolicySnapshotCodec.hydrate(stored.copy(chatId = 2), saved.policies.map { it.copy(chatId = 2) }, refs.map { it.copy(chatId = 2) }) }
    }

    @Test fun legacyAndUnsupportedPolicySchemasRemainInlineWithoutRewriting() {
        val legacy = RunPolicyFixture.run(raw = "{\"oldPolicy\":\"saved verbatim\"}")
        assertNull(RunPolicySnapshotCodec.normalize(legacy))
        assertEquals(legacy, RunPolicySnapshotCodec.hydrate(legacy, emptyList(), emptyList()))
        assertNull(RunPolicySnapshotCodec.normalize(RunPolicyFixture.run(raw = RunPolicyFixture.raw(requestedVersion = 99))))
        assertNull(RunPolicySnapshotCodec.normalize(RunPolicyFixture.run().copy(synthesisKey = "invalid-legacy-seal")))
    }

    @Test fun unportableReferenceImportIsAnExplicitRecoveryError() {
        val original = RunPolicyFixture.run()
        val saved = requireNotNull(RunPolicySnapshotCodec.normalize(original))
        assertPolicyFailure { RunPolicySnapshotCodec.normalize(original.copy(synthesisSnapshot = saved.snapshot)) }
    }

    private fun assertPolicyFailure(action: () -> Any?) {
        assertTrue(runCatching(action).exceptionOrNull() is RunPolicySnapshotException)
    }
}
