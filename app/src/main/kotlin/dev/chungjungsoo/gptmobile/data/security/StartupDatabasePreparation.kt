package dev.chungjungsoo.gptmobile.data.security

import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationOwner
import dev.chungjungsoo.gptmobile.data.diagnostics.CombinedOperationTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finish recovery first, then open on IO before startup observers contend for Room's lazy helper. */
internal suspend fun prepareStartupConversationDatabase(open: () -> Unit) {
    val openFailure = withContext(Dispatchers.IO) {
        try {
            CombinedOperationTrace.measure("database_startup_open", CombinedOperationOwner(screen = "Startup"), open)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Return the original database error across the dispatcher boundary. Coroutine stack
            // recovery can copy exceptions thrown here before the startup owner receives them.
            failure
        }
    }
    if (openFailure != null) throw openFailure
}
