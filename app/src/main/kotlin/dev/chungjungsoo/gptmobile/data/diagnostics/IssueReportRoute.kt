package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.Serializable

/** The destination and public author shown when the user approves these exact report bytes. */
@Serializable
internal data class IssueReportRoute(
    val kind: Kind,
    val origin: String? = null,
    val connectionUid: String? = null,
    val accountId: String? = null,
    val accountLogin: String? = null
) {
    @Serializable
    enum class Kind { SERVICE, GITHUB }

    fun valid(): Boolean = when (kind) {
        Kind.SERVICE -> origin != null && IssueReportSubmission.configured(origin) && connectionUid == null && accountId == null && accountLogin == null
        Kind.GITHUB -> origin == null && !connectionUid.isNullOrBlank() && accountId?.matches(Regex("[1-9][0-9]{0,19}")) == true && accountLogin?.matches(Regex("[A-Za-z0-9-]{1,39}")) == true
    }
}
