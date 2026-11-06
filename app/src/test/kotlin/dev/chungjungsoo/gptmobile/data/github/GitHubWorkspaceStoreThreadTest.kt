package dev.chungjungsoo.gptmobile.data.github

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GitHubWorkspaceStoreThreadTest {
    @Test fun coldConstructionDoesNotOpenPreferencesAndOperationsMoveToIo() = runBlocking {
        val caller = Thread.currentThread()
        val opens = AtomicInteger()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                assertNotEquals(caller, Thread.currentThread())
                opens.incrementAndGet()
                return super.getSharedPreferences("thread-test-$name", Context.MODE_PRIVATE)
            }
        }
        val store = GitHubWorkspaceStore(context)
        assertEquals(0, opens.get())
        val repository = GitHubRepositoryContext("owner", "repository", "main")
        store.set("connection", repository)
        assertEquals(repository, store.get("connection"))
        store.set("connection", null)
        assertNull(store.get("connection"))
        assertEquals(1, opens.get())
        store.updateSession("connection") { GitHubSessionSnapshot("connection", recentChatId = 0) }
        coroutineScope {
            repeat(40) {
                launch(Dispatchers.Default) {
                    store.updateSession("connection") { previous -> requireNotNull(previous).copy(recentChatId = requireNotNull(previous.recentChatId) + 1) }
                }
            }
        }
        assertEquals(40, store.getSession("connection")?.recentChatId)
        val reopened = GitHubWorkspaceStore(context)
        assertEquals(1, opens.get())
        assertEquals(40, reopened.getSession("connection")?.recentChatId)
        assertEquals(2, opens.get())
    }
}
