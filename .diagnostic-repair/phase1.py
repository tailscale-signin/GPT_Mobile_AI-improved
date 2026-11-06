from pathlib import Path
root = Path.cwd()
b = 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/'
def replace(path, old, new):
    p = root / path
    s = p.read_text()
    if s.count(old) != 1:
        raise RuntimeError('Source precondition failed: ' + path)
    p.write_text(s.replace(old, new))
replace(b+'github/GitHubRepositoryStateStore.kt', '''    suspend fun remove(scope: String, key: String) = mutex.withLock {
        val identity = "$scope:$key"
        memoryCharacters -= values.remove(identity)?.toString()?.length ?: 0
        directory?.let { File(it, digest(identity) + ".record").delete() }
        Unit
    }''', '''    suspend fun remove(scope: String, key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val identity = "$scope:$key"
            memoryCharacters -= values.remove(identity)?.toString()?.length ?: 0
            directory?.let { File(it, digest(identity) + ".record").delete() }
            Unit
        }
    }''')
replace(b+'github/GitHubRepositoryStateStore.kt', '''                    it.flush()
                }
                check(temp.renameTo(target))''', '''                    it.flush()
                    it.fd.sync()
                }
                check(temp.renameTo(target))''')
replace(b+'github/GatewayEngineeringClient.kt', '        capability.unavailableReason(graph = true)?.let { error(it) }', '        capability.unavailableReason(graph = true)?.let { throw GatewayGraphUnavailableException(it) }')
replace(b+'github/GatewayEngineeringClient.kt', 'private class UnsupportedEngineeringGateway : IllegalStateException(', '''/** Availability only: policy, identity, TLS and authorization failures must not use this type. */
internal open class GatewayGraphUnavailableException(message: String) : IllegalStateException(message)

private class UnsupportedEngineeringGateway : GatewayGraphUnavailableException(''')
replace(b+'agent/tool/RepositoryGraphTool.kt', 'import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent\n', 'import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent\nimport dev.chungjungsoo.gptmobile.data.github.GatewayGraphUnavailableException\n')
replace(b+'agent/tool/RepositoryGraphTool.kt', '''            // Only a transport failure of a status read may trigger one independent,
            // authorized native read. Never replay graph indexing/notes or bypass a
            // graph admission/permission rejection through another provider.
            val fallback = if (arguments["action"] == JsonPrimitive("status") && failure is java.io.IOException && failure !is javax.net.ssl.SSLException) {''', '''            // A transport/readiness failure of a status read may trigger one independent,
            // authorized native read. Typed readiness does not include graph policy,
            // identity or permission rejection. Indexing and notes are never replayed.
            val unavailable = failure is GatewayGraphUnavailableException ||
                failure is java.io.IOException && failure !is javax.net.ssl.SSLException
            val fallback = if (arguments["action"] == JsonPrimitive("status") && unavailable) {''')
replace(b+'github/GitHubRequestCircuit.kt', '''            // Permissions, validation and TLS failures keep their own explicit diagnostics.
            // An observed server rejection is evidence that the service is reachable.
            if (permit.probe) succeeded(permit)
''', '''            // Only an observed HTTP response proves reachability. Local validation,
            // cancellation and TLS failures must neither trip nor heal the circuit.
            if (failure is GitHubReadRetry.HttpFailure || failure is GitHubRequestRejected && failure.status in 400..499 && failure.status != 408) {
                succeeded(permit)
            } else if (permit.probe) {
                probing = false
            }
''')
replace(b+'github/GitHubInspection.kt', '''                    put("commit", if (resolved.s("type") == "commit") get("$root/commits/${segment(resolved.s("sha"))}") else JsonNull)''', '''                    put(
                        "commit",
                        if (resolved.s("type") == "commit") {
                            GitHubCommitDetail.present(
                                get("$root/commits/${segment(resolved.s("sha"))}?per_page=30&page=1").jsonObject,
                                page = 1,
                                perPage = 30,
                                includePatches = false
                            )
                        } else {
                            JsonNull
                        }
                    )''')
replace('app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/github/GitHubToolCompletenessTest.kt', '                    "/repos/o/r/commits/pinned" ->', '                    "/repos/o/r/commits/pinned?per_page=30&page=1" ->')
replace(b+'agent/tool/GitHubResultViews.kt', '''        if (value.toString().toByteArray().size <= minOf(1024, pageBytes / 4)) {
            target += buildJsonObject {
                put("pointer", pointer)
                put("value", value)
            }
            return
        }''', '''        val whole = buildJsonObject {
            put("pointer", pointer)
            put("value", value)
        }
        if (whole.toString().encodeToByteArray().size <= minOf(384, pageBytes / 4)) {
            target += whole
            return
        }''')
replace(b+'network/LocalServiceHealth.kt', '"connection abort", "connection reset", "broken pipe", "unexpected end", "socket closed"', '"connection abort", "connection reset", "connection closed before response arrived", "broken pipe", "unexpected end", "socket closed"')
