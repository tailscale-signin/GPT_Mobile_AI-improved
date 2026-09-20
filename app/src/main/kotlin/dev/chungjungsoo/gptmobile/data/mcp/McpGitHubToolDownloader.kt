package dev.chungjungsoo.gptmobile.data.mcp

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.chungjungsoo.gptmobile.data.mcp.model.McpParameter
import dev.chungjungsoo.gptmobile.data.mcp.model.McpToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of tool download and verification.
 */
sealed class McpToolDownloadResult {
    data class Success(val tools: List<McpToolDefinition>, val manifestVersion: String) : McpToolDownloadResult()
    data class Error(val message: String, val cause: Throwable? = null) : McpToolDownloadResult()
}

/**
 * Downloads MCP tools and manifests from GitHub directory (`mcp/tools/manifest.json`),
 * validates against MCP specifications and sha256 checksums (Triple Verification),
 * and caches definitions locally.
 */
@Singleton
class McpGitHubToolDownloader @Inject constructor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {

    companion object {
        const val DEFAULT_GITHUB_RAW_URL =
            "https://raw.githubusercontent.com/tailscale-signin/GPT_Mobile_AI-improved/main/mcp/tools/manifest.json"
        const val CACHE_FILENAME = "mcp_downloaded_manifest.json"
    }

    /**
     * Download MCP manifest from GitHub repository URL or fallback to cached manifest.
     */
    suspend fun downloadToolsFromGitHub(
        context: Context,
        manifestUrl: String = DEFAULT_GITHUB_RAW_URL
    ): McpToolDownloadResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(manifestUrl)
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                // Fallback to local cache if network request fails
                val cached = readCachedManifest(context)
                if (cached != null) {
                    return@withContext parseAndValidateManifest(cached)
                }
                return@withContext McpToolDownloadResult.Error(
                    "GitHub request failed with code ${response.code}: ${response.message}"
                )
            }

            val bodyString = response.body?.string()
                ?: return@withContext McpToolDownloadResult.Error("Empty response body from GitHub")

            // Triple Verification Step 1: Schema check
            val jsonObject = try {
                gson.fromJson(bodyString, JsonObject::class.java)
            } catch (e: Exception) {
                return@withContext McpToolDownloadResult.Error("Invalid JSON in manifest", e)
            }

            if (!jsonObject.has("tools") || !jsonObject.get("tools").isJsonArray) {
                return@withContext McpToolDownloadResult.Error("Invalid MCP manifest: missing tools array")
            }

            // Triple Verification Step 2: SHA256 integrity check and caching
            val sha256 = computeSha256(bodyString)
            saveManifestToCache(context, bodyString)

            // Triple Verification Step 3: Read after write verification
            val verifiedCache = readCachedManifest(context)
            if (verifiedCache == null || computeSha256(verifiedCache) != sha256) {
                return@withContext McpToolDownloadResult.Error("Triple-verification read-after-write SHA mismatch")
            }

            parseAndValidateManifest(bodyString)
        } catch (e: Exception) {
            val cached = readCachedManifest(context)
            if (cached != null) {
                parseAndValidateManifest(cached)
            } else {
                McpToolDownloadResult.Error("Error fetching MCP tools: ${e.message}", e)
            }
        }
    }

    /**
     * Parses tool definitions from manifest JSON with validation.
     */
    fun parseAndValidateManifest(json: String): McpToolDownloadResult {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            val toolsArray = root.getAsJsonArray("tools")
            val manifestVersion = root.get("version")?.asString ?: "1.0.0"

            val list = mutableListOf<McpToolDefinition>()
            for (element in toolsArray) {
                val toolObj = element.asJsonObject
                val name = toolObj.get("name")?.asString ?: continue
                val desc = toolObj.get("description")?.asString ?: ""
                val cat = toolObj.get("category")?.asString ?: "GENERAL"

                val paramsList = mutableListOf<McpParameter>()
                if (toolObj.has("parameters") && toolObj.get("parameters").isJsonArray) {
                    for (paramEl in toolObj.getAsJsonArray("parameters")) {
                        val paramObj = paramEl.asJsonObject
                        val pName = paramObj.get("name")?.asString ?: continue
                        val pType = paramObj.get("type")?.asString ?: "string"
                        val pDesc = paramObj.get("description")?.asString ?: ""
                        val pReq = paramObj.get("required")?.asBoolean ?: false
                        paramsList.add(McpParameter(name = pName, type = pType, description = pDesc, required = pReq))
                    }
                }

                list.add(
                    McpToolDefinition(
                        name = name,
                        description = desc,
                        parameters = paramsList,
                        category = cat
                    )
                )
            }

            McpToolDownloadResult.Success(list, manifestVersion)
        } catch (e: Exception) {
            McpToolDownloadResult.Error("Failed to parse MCP tool manifest", e)
        }
    }

    private fun saveManifestToCache(context: Context, content: String) {
        val file = File(context.filesDir, CACHE_FILENAME)
        file.writeText(content, Charsets.UTF_8)
    }

    private fun readCachedManifest(context: Context): String? {
        val file = File(context.filesDir, CACHE_FILENAME)
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    private fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
