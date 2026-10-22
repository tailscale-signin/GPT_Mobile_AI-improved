package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeOperationJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completedGpuFailureDoesNotQuarantineCpuFallbackButCpuCrashStillDoes() {
        val root = temporary.newFolder()
        val journal = NativeOperationJournal(root)
        journal.before("artifact|runtime|gpu", "LOAD")
        journal.finished() // A caught GPU failure returned and its resources were released.
        journal.before("artifact|runtime|cpu", "LOAD")
        journal.finished()
        journal.before("artifact|runtime|cpu", "GENERATE")
        val restarted = NativeOperationJournal(root, exitCause = { _, _ -> "NATIVE_CRASH" })
        restarted.before("artifact|runtime|gpu", "LOAD")
        restarted.finished()
        assertTrue(runCatching { restarted.before("artifact|runtime|cpu", "GENERATE") }.isFailure)
    }

    @Test fun interruptedTupleStaysBlockedAfterAnotherModelRuns() {
        val root = temporary.newFolder()
        NativeOperationJournal(root).before("artifact-A|runtime-1|firmware|npu")
        val restarted = NativeOperationJournal(root)
        restarted.before("artifact-B|runtime-1|firmware|npu")
        restarted.finished()
        assertTrue(runCatching { restarted.before("artifact-A|runtime-1|firmware|npu") }.isFailure)
        restarted.before("artifact-A|runtime-2|firmware|npu")
        restarted.finished()
    }

    @Test fun normalCompletionOrCancellationDoesNotQuarantine() {
        val root = temporary.newFolder()
        val journal = NativeOperationJournal(root)
        journal.before("tuple")
        journal.finished()
        NativeOperationJournal(root).before("tuple")
    }

    @Test fun lowMemoryExitDoesNotBrandTheArtifactIncompatible() {
        val root = temporary.newFolder()
        NativeOperationJournal(root).before("tuple")
        val recovered = NativeOperationJournal(root, exitCause = { _, _ -> "LOW_MEMORY" })
        recovered.before("tuple")
        recovered.finished()
        assertTrue(root.resolve("last-interruption").readText().contains("LOW_MEMORY"))
    }

    @Test fun nativeCrashRemainsQuarantinedUntilExplicitVerification() {
        val root = temporary.newFolder()
        NativeOperationJournal(root).before("tuple")
        val recovered = NativeOperationJournal(root, exitCause = { _, _ -> "NATIVE_CRASH" })
        assertTrue(runCatching { recovered.before("tuple") }.isFailure)
        recovered.allowVerification("tuple")
        recovered.before("tuple")
    }
}
