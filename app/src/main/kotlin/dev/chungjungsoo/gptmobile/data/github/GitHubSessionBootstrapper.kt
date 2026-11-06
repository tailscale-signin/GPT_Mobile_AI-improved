package dev.chungjungsoo.gptmobile.data.github

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

/** Local display context. None of these fields grants permission to dispatch a tool. */
@Serializable
data class GitHubSessionSnapshot(
    val connectionUid: String,
    val accountIdentity: String? = null,
    val accountLogin: String? = null,
    val repositoryId: String? = null,
    val repository: GitHubRepositoryContext? = null,
    val defaultBranch: String? = null,
    val observedHeadSha: String? = null,
    val headObservedAtMillis: Long? = null,
    val profileUid: String? = null,
    val recentChatId: Int? = null,
    val hasRecoverableDraft: Boolean = false
)

enum class GitHubComposerReadiness { NOT_READY, REFRESH_REQUIRED, READY, UNAVAILABLE }
enum class GitHubOptionalReadiness { NOT_REQUESTED, LOADING, READY, UNAVAILABLE }
enum class GitHubBackgroundJobState { IDLE, REFRESHING, WRITING }

/** Each screen/session switch invalidates presentation work without cancelling domain writes. */
class GitHubSessionEpoch {
    private val epoch = AtomicLong()
    fun next(): Long = epoch.incrementAndGet()
    fun current(): Long = epoch.get()
    fun accepts(value: Long): Boolean = epoch.get() == value
}

@Singleton
class GitHubSessionBootstrapper @Inject constructor(private val store: GitHubWorkspaceStore) {
    fun read(connectionUid: String): GitHubSessionSnapshot? = store.getSession(connectionUid)?.takeIf { it.connectionUid == connectionUid }

    fun rememberRepository(snapshot: GitHubSessionSnapshot) = store.updateSession(snapshot.connectionUid) { previous ->
        val sameScope = previous != null && previous.repository == snapshot.repository && previous.accountIdentity == snapshot.accountIdentity
        snapshot.copy(
            profileUid = previous?.profileUid.takeIf { sameScope },
            recentChatId = previous?.recentChatId.takeIf { sameScope }
        )
    }

    fun rememberConversation(context: GitHubConversationContext, chatId: Int?, hasRecoverableDraft: Boolean) = store.updateSession(context.connectionUid) { previous ->
        val sameRepository = previous?.repository == context.repository
        GitHubSessionSnapshot(
            connectionUid = context.connectionUid,
            accountIdentity = previous?.accountIdentity,
            accountLogin = previous?.accountLogin,
            repositoryId = previous?.repositoryId.takeIf { sameRepository },
            repository = context.repository,
            defaultBranch = previous?.defaultBranch.takeIf { sameRepository },
            observedHeadSha = context.commitSha,
            headObservedAtMillis = context.observedAtMillis,
            profileUid = context.mainProfileUid,
            recentChatId = chatId,
            hasRecoverableDraft = hasRecoverableDraft
        )
    }
}
