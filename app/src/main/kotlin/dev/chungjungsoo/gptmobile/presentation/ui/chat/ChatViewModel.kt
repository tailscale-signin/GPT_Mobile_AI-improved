        viewModelScope.launch {
            val connections = toolConnectionRepository.getAllConnections()
            _availableChatTools.update { ChatToolUtils.buildAvailableChatTools(connections) }
        }