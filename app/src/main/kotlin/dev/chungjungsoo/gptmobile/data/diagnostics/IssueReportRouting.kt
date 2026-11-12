package dev.chungjungsoo.gptmobile.data.diagnostics

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.chungjungsoo.gptmobile.BuildConfig
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.github.GitHubApiOrigins
import dev.chungjungsoo.gptmobile.data.github.GitHubPublicAuthCoordinator
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** No credential enters an outbox, report body, URL, log or WorkManager request. */
@Singleton
class IssueReportRouting @Inject constructor(
    private val connections: ToolConnectionRepository,
    private val authorization: GitHubPublicAuthCoordinator
) {
    internal suspend fun options(): List<IssueReportRoute> = buildList {
        serviceRoute()?.let(::add)
        if (BuildConfig.ISSUE_REPORT_GITHUB_ENABLED) {
            connections.listConnections().filter(::eligible).forEach { connection ->
                authorization.connectedAccount(connection.connectionUid)?.let { account ->
                    val route = IssueReportRoute(IssueReportRoute.Kind.GITHUB, connectionUid = connection.connectionUid, accountId = account.id, accountLogin = account.login)
                    if (route.valid()) add(route)
                }
            }
        }
    }

    internal suspend fun send(outbox: IssueReportOutbox, reportId: String): IssueReportSubmission.Outcome {
        val entry = checkNotNull(outbox.load(reportId))
        val route = entry.route ?: return IssueReportSubmission.Failure(if (entry.mayHaveBeenSubmitted) "REPORT_LEGACY_DESTINATION_UNKNOWN" else "REPORT_DESTINATION_REQUIRED", retryable = !entry.mayHaveBeenSubmitted, automaticRetry = false)
        return when (route.kind) {
            IssueReportRoute.Kind.SERVICE -> {
                if (serviceRoute() == null) return IssueReportSubmission.Failure("REPORT_DESTINATION_UNAVAILABLE", retryable = true, automaticRetry = false)
                // The approved service origin survives updates. Changing a repository
                // variable cannot silently reroute an already attempted report.
                IssueReportSubmission(requireNotNull(route.origin)).send(entry.reportId, entry.payload) { outbox.dispatching(reportId, route) }
            }
            IssueReportRoute.Kind.GITHUB -> {
                if (!BuildConfig.ISSUE_REPORT_GITHUB_ENABLED) return IssueReportSubmission.Failure("REPORT_DESTINATION_UNAVAILABLE", retryable = true, automaticRetry = false)
                val connection = connections.getConnection(requireNotNull(route.connectionUid))?.takeIf(::eligible)
                    ?: return IssueReportSubmission.Failure("REPORT_GITHUB_SIGN_IN_REQUIRED", retryable = true, automaticRetry = false)
                val token = try {
                    val value = authorization.accessToken(connection)
                    val account = authorization.connectedAccount(connection.connectionUid)
                    if (account?.id != route.accountId) return IssueReportSubmission.Failure("REPORT_GITHUB_ACCOUNT_CHANGED", retryable = true, automaticRetry = false)
                    // Recheck policy and origin after refresh, immediately before use.
                    if (connections.getConnection(connection.connectionUid) != connection) return IssueReportSubmission.Failure("REPORT_GITHUB_SIGN_IN_REQUIRED", retryable = true, automaticRetry = false)
                    value
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return IssueReportSubmission.Failure("REPORT_GITHUB_SIGN_IN_REQUIRED", retryable = true, automaticRetry = false)
                }
                IssueReportGitHubSubmission().send(entry, token) { outbox.dispatching(reportId, route) }
            }
        }
    }

    private fun eligible(connection: ToolConnection): Boolean = connection.type == "GITHUB" &&
        connection.secretRef != null &&
        connection.toolPolicy in setOf("ASK_WRITES", "TRUSTED") &&
        !connection.allowCleartext &&
        runCatching { GitHubApiOrigins.fromEndpoint(connection.endpointUrl) == GitHubApiOrigins.GITHUB_COM }.getOrDefault(false)

    internal companion object {
        fun serviceRoute(): IssueReportRoute? = BuildConfig.ISSUE_REPORT_ORIGIN.takeIf(IssueReportSubmission::configured)?.let { IssueReportRoute(IssueReportRoute.Kind.SERVICE, origin = it) }
        fun forContext(context: Context): IssueReportRouting = EntryPointAccessors.fromApplication(context, IssueReportDependencies::class.java).issueReportRouting()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface IssueReportDependencies {
    fun issueReportRouting(): IssueReportRouting
}
