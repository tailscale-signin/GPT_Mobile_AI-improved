package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Repository display context only. Every preference access stays off the UI thread. */
@Singleton
class GitHubWorkspaceStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences by lazy { context.getSharedPreferences("github_workspace", Context.MODE_PRIVATE) }
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun session(connectionUid: String): GitHubSessionSnapshot? = preferences.getString("session:$connectionUid", null)?.let { value ->
        runCatching { json.decodeFromString<GitHubSessionSnapshot>(value) }.getOrNull()
    }

    suspend fun getSession(connectionUid: String): GitHubSessionSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock { session(connectionUid) }
    }

    suspend fun updateSession(connectionUid: String, update: (GitHubSessionSnapshot?) -> GitHubSessionSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val snapshot = update(session(connectionUid))
            require(snapshot.connectionUid == connectionUid)
            preferences.edit().putString("session:$connectionUid", json.encodeToString(snapshot)).apply()
        }
    }

    suspend fun get(connectionUid: String): GitHubRepositoryContext? = withContext(Dispatchers.IO) {
        mutex.withLock {
            preferences.getString(connectionUid, null)?.let { runCatching { json.decodeFromString<GitHubRepositoryContext>(it) }.getOrNull() }
        }
    }

    suspend fun set(connectionUid: String, selection: GitHubRepositoryContext?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            preferences.edit().apply {
                if (selection == null) remove(connectionUid) else putString(connectionUid, json.encodeToString(selection))
            }.apply()
        }
    }
}
