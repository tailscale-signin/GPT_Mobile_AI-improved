package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitHubWorkflowDispatchTest {
    private val source = """
        name: Build
        on:
          push:
          workflow_dispatch:
            inputs:
              publish:
                type: boolean
                required: true
              mode:
                type: choice
                options:
                  - quick
                  - full
                default: quick
        jobs:
          test:
            steps:
              - run: |
                  echo workflow_dispatch
    """.trimIndent()
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun declaredTypesChoicesAndTopLevelRefAreEnforced() {
        GitHubWorkflowDispatch.validate(source, json("""{"publish":false,"mode":"full"}"""))
        for (inputs in listOf("""{"publish":true,"ref":"main"}""", """{"publish":"perhaps"}""", """{"publish":true,"mode":"invented"}""", "{}")) {
            assertEquals("WORKFLOW_INPUTS_INVALID", assertThrows(GitHubWorkflowDispatch.Invalid::class.java) { GitHubWorkflowDispatch.validate(source, json(inputs)) }.code)
        }
        // Registration on the default branch needs a trigger, not target-ref input values.
        GitHubWorkflowDispatch.validate(source, JsonObject(emptyMap()), validateInputs = false)
    }

    @Test fun pushOnlyAliasesAndWorkflowCallNeverProveManualDispatch() {
        for (definition in listOf("on: [push, pull_request]", "on:\n  workflow_call:\n    inputs:\n      ref:\n        type: string")) {
            assertEquals("WORKFLOW_DISPATCH_UNSUPPORTED", assertThrows(GitHubWorkflowDispatch.Invalid::class.java) { GitHubWorkflowDispatch.validate(definition, JsonObject(emptyMap())) }.code)
        }
        assertEquals("WORKFLOW_SCHEMA_UNVERIFIED", assertThrows(GitHubWorkflowDispatch.Invalid::class.java) { GitHubWorkflowDispatch.validate("on:\n  workflow_dispatch: *hidden", JsonObject(emptyMap())) }.code)
        GitHubWorkflowDispatch.validate("'on': [push, workflow_dispatch]", JsonObject(emptyMap()))
        GitHubWorkflowDispatch.validate("on:\n  workflow_dispatch: {}", JsonObject(emptyMap()))
    }
}
