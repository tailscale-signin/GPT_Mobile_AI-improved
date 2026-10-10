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

    @Test fun normalCompletionOrCancellationDoesNotQuarantine() {
        val root = temporary.newFolder()
        val journal = NativeOperationJournal(root)
        journal.before("tuple")
        journal.finished()
        NativeOperationJournal(root).before("tuple")
    }
}
