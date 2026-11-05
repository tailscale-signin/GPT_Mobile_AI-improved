package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject

/** Validation rejects the requested read, even if its pinned tree was fetched first. */
internal class GitHubReadValidation(val code: String, val details: JsonObject) : IllegalArgumentException(details.toString())
