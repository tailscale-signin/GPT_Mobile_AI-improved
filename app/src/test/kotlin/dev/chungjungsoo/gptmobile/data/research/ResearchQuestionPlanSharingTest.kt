package dev.chungjungsoo.gptmobile.data.research

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ResearchQuestionPlanSharingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val directory: File get() = File(context.filesDir, "research-history")
    private val task = "Research device request limits"
    private val plan = ResearchPlanContract(listOf(ResearchQuestion("Q1", "What is the documented limit?")), listOf(ResearchQuery("QRY1", "device request limit", listOf("Q1"))))

    @Before fun cleanBefore() {
        directory.deleteRecursively()
    }

    @After fun cleanAfter() {
        directory.deleteRecursively()
    }

    @Test fun acceptedParticipantsReuseImmutablePlanAcrossScopesAndRestartWithoutSourceAccess() = runBlocking {
        val store = ResearchSessionStore(context)
        store.acceptTask(1, "root", 7, task, listOf("a", "b"), "a", "settings", "authority", true)
        val first = store.journal("run-a", 1, true, "root", authority = "private-a", ownerScope = "a")
        assertEquals(plan, first.publicQuestionPlan(task, plan))
        first.save(ResearchSnapshot(task, sources = listOf(ResearchSource("S1", "https://example.org/private", "Private captured page", "Read", "A private authorized source describes the device request limits and its surrounding constraints."))))
        val sibling = ResearchSessionStore(context).journal("run-b", 1, true, "root", authority = "private-b", ownerScope = "b")
        assertEquals(plan, sibling.publicQuestionPlan(task))
        assertEquals(plan, sibling.publicQuestionPlan(task, plan.copy(questions = listOf(ResearchQuestion("Q2", "A competing plan")), queries = listOf(ResearchQuery("QRY2", "another query", listOf("Q2"))))))
        assertNull(sibling.load(task))
    }

    @Test fun changedRootChatTaskAndUnacceptedParticipantCannotReadOrWriteThePlan() = runBlocking {
        val store = ResearchSessionStore(context)
        store.acceptTask(1, "root", 7, task, listOf("a"), "a", "settings", "authority", true)
        val owner = store.journal("owner", 1, true, "root", ownerScope = "a")
        owner.publicQuestionPlan(task, plan)
        assertNull(store.journal("other-root", 1, true, "other-root", ownerScope = "a").publicQuestionPlan(task, plan))
        assertNull(store.journal("other-chat", 2, true, "root", ownerScope = "a").publicQuestionPlan(task, plan))
        assertNull(store.journal("stranger", 1, true, "root", ownerScope = "stranger").publicQuestionPlan(task, plan))
        assertNull(owner.publicQuestionPlan("A different research task", plan))
        store.resumeGeneration(1, "root", "resume", true)
        assertNull(owner.publicQuestionPlan(task, plan))
    }

    @Test fun temporaryAcceptedPlanDoesNotWriteAnyTaskOrPlanToDisk() = runBlocking {
        val store = ResearchSessionStore(context)
        store.acceptTask(1, "temporary", 8, task, listOf("a", "b"), "a", "settings", "authority", false)
        store.journal("a", 1, false, "temporary", ownerScope = "a").publicQuestionPlan(task, plan)
        assertEquals(plan, store.journal("b", 1, false, "temporary", ownerScope = "b").publicQuestionPlan(task))
        assertFalse(directory.listFiles().orEmpty().any { it.extension in setOf("task", "plan") })
        assertNull(ResearchSessionStore(context).journal("new", 1, false, "temporary", ownerScope = "a").publicQuestionPlan(task))
    }
}
