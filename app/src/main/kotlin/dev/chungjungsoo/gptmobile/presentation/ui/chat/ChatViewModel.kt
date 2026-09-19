        sendQuestion(questionText, _selectedAttachments.value)
    }

    fun sendContinueResponse() {
        sendQuestion("continue", emptyList())
    }

    fun sendPromptResponse(promptText: String) {
        if (promptText.isNotBlank()) {
            sendQuestion(promptText.trim(), emptyList())
        }
    }

    fun cancelActiveRuns() {
        _chatRoom.value.id.takeIf { it > 0 }?.let(agentRunCoordinator::cancelChat)
    }