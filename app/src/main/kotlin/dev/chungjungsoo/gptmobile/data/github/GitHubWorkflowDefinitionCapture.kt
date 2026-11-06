package dev.chungjungsoo.gptmobile.data.github

import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An unavailable definition does not discard other files from a pinned catalog page. */
internal object GitHubWorkflowDefinitionCapture {
    suspend fun capture(readSource: suspend () -> String): JsonObject {
        val source = try {
            readSource()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (security: SSLException) {
            throw security
        } catch (failure: GitHubBlobContentFailure) {
            return unavailable(failure.code, "The definition is not supported bounded text; inspect its source.")
        } catch (failure: GitHubRequestRejected) {
            // Permission, rate-limit and admission failures stop the page. A missing
            // immutable blob is a per-file omission, not an authorization fallback.
            if (failure.status != 404) throw failure
            return unavailable("GITHUB_NOT_FOUND_OR_INACCESSIBLE", "The definition blob could not be read at the pinned revision.")
        } catch (failure: GitHubResponseTooLarge) {
            return unavailable("GITHUB_RESPONSE_TOO_LARGE", "The definition exceeds the bounded response budget; inspect bounded source ranges.")
        } catch (failure: SerializationException) {
            return unavailable("GITHUB_UNEXPECTED_RESPONSE", "The definition response could not be decoded.")
        } catch (failure: IOException) {
            val code = if (failure is GitHubReadRetry.HttpFailure) "GITHUB_TRANSIENT_SERVER" else "GITHUB_TRANSIENT_NETWORK"
            return unavailable(code, "The definition could not be read after bounded transport retries.")
        }
        if (source.length > 256 * 1024) return unavailable("GITHUB_WORKFLOW_METADATA_LIMIT", "The definition exceeds the metadata budget; inspect bounded source ranges.")
        return withContext(Dispatchers.Default) {
            JsonObject(GitHubWorkflowMetadata.parse(source) + ("metadata_available" to JsonPrimitive(true)))
        }
    }

    private fun unavailable(code: String, reason: String) = buildJsonObject {
        put("metadata_available", false)
        put("error_code", code)
        put("reason", reason)
        put("retry_automatically", false)
    }
}
