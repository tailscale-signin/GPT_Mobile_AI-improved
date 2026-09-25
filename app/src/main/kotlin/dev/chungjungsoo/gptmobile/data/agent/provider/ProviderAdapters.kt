                    // Parse OpenRouter options or fallback to routing if legacy
                    val (parsedOpenRouterOptions, parsedRouting) = if (isOpenRouter) {
                        if (!platform.openRouterRouting.isNullOrBlank()) {
                            val asOptions = runCatching { json.decodeFromString<OpenRouterOptions>(platform.openRouterRouting) }.getOrNull()
                            if (asOptions != null && (asOptions.provider != null || asOptions.maxTokens != null || asOptions.stream != null || asOptions.repetitionPenalty != null || asOptions.seed != null)) {
                                asOptions to asOptions.provider?.normalized()
                            } else {
                                val routing = runCatching { json.decodeFromString<OpenRouterProviderRouting>(platform.openRouterRouting) }.getOrNull()
                                null to routing?.normalized()
                            }
                        } else {
                            val defaultOpts = OpenRouterOptions.createDefault()
                            defaultOpts to defaultOpts.provider?.normalized()
                        }
                    } else {
                        null to null
                    }
