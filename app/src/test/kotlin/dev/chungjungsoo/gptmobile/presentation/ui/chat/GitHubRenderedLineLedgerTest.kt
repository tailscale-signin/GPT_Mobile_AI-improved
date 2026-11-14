package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubRenderedLineLedgerTest {
    private val ledger = GitHubRenderedLineLedger()
    private fun observe(text: String, lines: Int, geometry: Int = 300, key: String = "paragraph", sourceOffset: Int = 0, updates: Boolean = true, reflowedPrevious: Int? = null) =
        ledger.observe(key, "document", sourceOffset, text, lines, geometry, updates, reflowedPrevious)

    @Test fun actualWrappedLinesCountWhileTokensOnTheSameLineDoNot() {
        ledger.arm()
        observe("A short line", 1)
        assertEquals(1, ledger.takeLines())
        observe("A short line grows", 1)
        assertEquals(0, ledger.takeLines())
        observe("A short line grows long enough to wrap", 3)
        assertEquals(2, ledger.takeLines())
    }

    @Test fun fontWidthAndCounterChangesNeverInventGeneratedLines() {
        ledger.arm()
        observe("Retained text", 1)
        ledger.takeLines()
        observe("Retained text", 3, geometry = 100)
        assertEquals(0, ledger.takeLines())
        observe("Check 1/8", 1, key = "counter", updates = false)
        assertEquals(1, ledger.takeLines())
        observe("Check 100/800", 2, key = "counter", updates = false)
        assertEquals(0, ledger.takeLines())
    }

    @Test fun simultaneousAppendAndReflowCompareTheOldPrefixUnderTheNewGeometry() {
        ledger.arm()
        observe("Existing paragraph", 3, geometry = 100)
        ledger.takeLines()
        // A wider window reduces the old prefix to one line, and the new text
        // adds two actual rendered lines: three minus three would incorrectly be zero.
        observe("Existing paragraph plus newly generated text", 3, geometry = 300, reflowedPrevious = 1)
        assertEquals(2, ledger.takeLines())
    }

    @Test fun reattachmentBaselinesPendingUnmeasuredContentAndOnlyCountsLaterParagraphs() {
        ledger.document("document", 100)
        ledger.arm()
        observe("An existing paragraph whose layout arrived late", 5, sourceOffset = 0)
        assertEquals(0, ledger.takeLines())
        observe("New paragraph", 3, key = "new", sourceOffset = 101)
        assertEquals(3, ledger.takeLines())
        ledger.baseline()
        observe("New paragraph with detached output", 7, key = "new", sourceOffset = 101)
        ledger.arm()
        observe("New paragraph with detached output", 7, key = "new", sourceOffset = 101)
        assertEquals(0, ledger.takeLines())
        observe("New paragraph with detached output and a fresh line", 8, key = "new", sourceOffset = 101)
        assertEquals(1, ledger.takeLines())
    }

    @Test fun revealingRetainedRowsAndPagesDoesNotChargeForTheirFirstLayout() {
        ledger.arm()
        ledger.observe("history", "document", 0, "An earlier three-line summary", 3, 300, firstAppearanceAddsLines = false)
        ledger.observe("page-two", "document", 1200, "A manually revealed page", 40, 300, firstAppearanceAddsLines = false)
        assertEquals(0, ledger.takeLines())
        ledger.observe("fresh", "document", 0, "A genuinely new summary", 3, 300, firstAppearanceAddsLines = true)
        assertEquals(3, ledger.takeLines())
    }

    @Test fun aTableReflowUsesWholeRowIdentityWhenItsTallestCellChanges() {
        ledger.arm()
        observe("Cell A | Cell B", 3, geometry = 100, key = "row")
        assertEquals(3, ledger.takeLines())
        observe("Cell A | Cell B", 2, geometry = 200, key = "row")
        assertEquals(0, ledger.takeLines())
        observe("Cell A | Cell B with new text", 4, geometry = 300, key = "row", reflowedPrevious = 1)
        assertEquals(3, ledger.takeLines())
    }

    @Test fun paragraphPromotionToATableKeepsTheExistingSourceBlockBaseline() {
        ledger.arm()
        observe("Name | Status", 1, geometry = 300, key = "source:0:0")
        assertEquals(1, ledger.takeLines())
        observe("Name | Status", 1, geometry = 150, key = "source:0:0")
        observe("One | Ready", 1, geometry = 150, key = "source:30:0", sourceOffset = 30)
        assertEquals(1, ledger.takeLines())
    }

    @Test fun anEmptyPlaceholderDoesNotConsumeTheFirstGeneratedLine() {
        ledger.arm()
        observe("", 1)
        assertEquals(0, ledger.takeLines())
        observe("The first generated line", 1)
        assertEquals(1, ledger.takeLines())
    }
}
