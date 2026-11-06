from pathlib import Path
root = Path.cwd()
b = 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/'
def replace(path, old, new):
    p = root / path
    s = p.read_text()
    if s.count(old) != 1:
        raise RuntimeError('Source precondition failed: ' + path)
    p.write_text(s.replace(old, new))
replace(b+'agent/tool/GitHubOperationTool.kt', '                    listOf("stage_id", "branch", "message", "expected_head_sha", "from_branch").forEach { properties[it] = field("string") }\n', '''                    listOf("stage_id", "branch", "message", "expected_head_sha", "from_branch").forEach { properties[it] = field("string") }
                    properties["select_created_branch"] = buildJsonObject {
                        put("type", "boolean")
                        put("description", "For action=branch only. Defaults to true. Use false for an explicitly authorized disposable fixture; read back its ref without changing the selected workspace.")
                    }
''')
replace(b+'agent/tool/GitHubTool.kt', '''                    put(
                        "branch",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Branch name for create_branch or update_file.")
                        }
                    )''', '''                    put(
                        "branch",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Branch name for create_branch or update_file.")
                        }
                    )
                    put("select_created_branch", buildJsonObject { put("type", "boolean") })''')
replace(b+'github/GitHubSelfTestFixtures.kt', '''                        put("from_branch", branch)
                        put("expected_head_sha", sha)''', '''                        put("from_branch", branch)
                        put("expected_head_sha", sha)
                        put("select_created_branch", false)''')
replace(b+'github/GitHubBranchTransition.kt', '        if (!(operation == "github_commit" && action == "branch" || operation == "github_context" && action == "resolve")) return null\n', '        if (!(operation == "github_commit" && action == "branch" || operation == "github_context" && action == "resolve")) return null\n        if (operation == "github_commit" && arguments["select_created_branch"] == JsonPrimitive(false)) return null\n')
replace(b+'agent/tool/GitHubTool.kt', '''            val receipt = jsonParser.parseToJsonElement(text).jsonObject
            val selection = try {
                workspaceClient.execute(
                    "resolve_context",
                    buildJsonObject {
                        put("owner", owner)
                        put("repo", repo)
                        put("ref", branch)
                        put("refresh", true)
                    }
                ).jsonObject
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            successResult(''', '''            val receipt = jsonParser.parseToJsonElement(text).jsonObject
            val selectCreated = arguments["select_created_branch"] != JsonPrimitive(false)
            val selection = try {
                workspaceClient.execute(
                    if (selectCreated) "resolve_context" else "get_branch_head",
                    buildJsonObject {
                        put("owner", owner)
                        put("repo", repo)
                        put("ref", branch)
                        put("branch", branch)
                        put("refresh", true)
                    }
                ).jsonObject
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            successResult(''')
replace(b+'agent/tool/GitHubTool.kt', '''                    put("remote_state_verified", selection?.get("commit_sha") == JsonPrimitive(sha) && selection?.get("ref") == JsonPrimitive(branch))
                    put("upstream_completed", true)
                    selection?.let { put("context", it) } ?: put("context_refresh_required", true)''', '''                    val selected = selectCreated && selection?.get("commit_sha") == JsonPrimitive(sha) && selection?.get("ref") == JsonPrimitive(branch)
                    val fixtureVerified = !selectCreated && selection?.get("ref") == JsonPrimitive("refs/heads/$branch") &&
                        (selection?.get("object") as? JsonObject)?.get("sha") == JsonPrimitive(sha)
                    put("remote_state_verified", selected || fixtureVerified)
                    put("context_selected", selected)
                    put("upstream_completed", true)
                    if (selectCreated) {
                        selection?.let { put("context", it) } ?: put("context_refresh_required", true)
                    } else {
                        put("selection_preserved", true)
                        selection?.let { put("ref_observation", it) }
                    }''')
replace(b+'agent/tool/GitHubResultViews.kt', '''                    val args = continuationArguments(operation, request, child)
                    target += buildJsonObject''', '''                    // A tag inspection embeds a pinned commit page, not another tag page.
                    // Preserve the authorized connection suffix while selecting the correct contract.
                    val targetOperation = if (operation == "github_release" && child["action"] == JsonPrimitive("get_commit_detail")) "github_context" else operation
                    val targetName = targetOperation + modelName.removePrefix(operation)
                    val args = continuationArguments(targetOperation, request, child)
                    target += buildJsonObject''')
replace(b+'agent/tool/GitHubResultViews.kt', '''                                put("tool", modelName)
                                put("arguments", args)''', '''                                put("tool", targetName)
                                put("arguments", args)''')
replace(b+'agent/tool/GitHubResultViews.kt', '''        isError = true,
        errorCode = "RETAINED_RESULT_CAPACITY"
    )''', '''        isError = true,
        errorCode = "RETAINED_RESULT_CAPACITY",
        retainedContent = upstream.retainedContent ?: upstream.content.takeIf { value.toString().encodeToByteArray().size <= MAX_SOURCE_BYTES },
        executionReceipt = JsonObject(
            upstream.executionReceipt.orEmpty() + buildJsonObject {
                put("upstream_completed", !upstream.isError)
                put("presentation_state", "CAPACITY_REJECTED")
                put("original_available_for_retention", value.toString().encodeToByteArray().size <= MAX_SOURCE_BYTES)
                // This is execution evidence, not proof that the view or the task was delivered.
                put("upstream_execution_state", if (upstream.isError) "FAILED" else "COMPLETED")
                upstream.errorCode?.let { put("upstream_error_code", it) }
            }
        )
    )''')
replace(b+'github/GitHubExecutionLedger.kt', '            receipt["success"] == JsonPrimitive(true) -> ReceiptState.COMPLETED', '''            receipt["success"] == JsonPrimitive(true) ||
                receipt.text("error_code") == "RETAINED_RESULT_CAPACITY" &&
                execution?.get("upstream_completed") == JsonPrimitive(true) &&
                execution?.text("upstream_execution_state") == "COMPLETED" -> ReceiptState.COMPLETED''')
replace(b+'github/GitHubExecutionLedger.kt', '            put("receipt_count", receipts.size)\n', '            put("receipt_count", receipts.size)\n            put("presentation_failures", receipts.values.count { (it["execution"] as? JsonObject)?.text("presentation_state") == "CAPACITY_REJECTED" })\n')
