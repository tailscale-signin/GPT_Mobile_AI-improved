package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.Serializable

@Serializable
enum class GitHubEngineeringMemoryMode { SESSION_ONLY, PERSISTENT, HYBRID }
