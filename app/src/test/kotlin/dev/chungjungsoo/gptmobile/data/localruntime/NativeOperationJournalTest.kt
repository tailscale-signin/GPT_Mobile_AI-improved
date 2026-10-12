package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeOperationJournalTest {
    @get:Rule val temporary = TemporaryFolder()

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

    @Test fun staleOperationTokenCannotClearANewerRequest() {
        val root = temporary.newFolder()
        val journal = NativeOperationJournal(root)
        val first = journal.before("tuple")
        journal.finished(first)
        val second = journal.before("tuple")
        journal.finished(first)
        assertTrue(root.resolve("pending").exists())
        journal.finished(second)
        assertTrue(!root.resolve("pending").exists())
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
