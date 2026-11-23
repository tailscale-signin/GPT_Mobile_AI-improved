package dev.chungjungsoo.gptmobile.data.security

import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finish recovery first, then open on IO before startup observers contend for Room's lazy helper. */
internal suspend fun prepareStartupConversationDatabase(open: () -> Unit) = withContext(Dispatchers.IO) {
    CombinedOperationTrace.measure("database_startup_open", CombinedOperationOwner(screen = "Startup"), open)
}
