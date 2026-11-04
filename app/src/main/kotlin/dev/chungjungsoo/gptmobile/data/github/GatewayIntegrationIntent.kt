package dev.chungjungsoo.gptmobile.data.github

internal fun isGatewayIntegrationTask(task: String): Boolean = Regex("(?i)\\bgateway\\b").containsMatchIn(task) &&
    Regex("(?i)\\b(?:test|check|verify|diagnos\\w*)\\b").containsMatchIn(task) &&
    !Regex("(?i)\\b(?:implement|fix|repair|build|add|change)\\b").containsMatchIn(task)
