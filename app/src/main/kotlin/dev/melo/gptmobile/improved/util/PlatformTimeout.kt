package dev.melo.gptmobile.improved.util

import dev.melo.gptmobile.improved.data.model.ClientType

fun getPlatformConnectTimeoutSeconds(platform: ClientType): Long = when (platform) {
    ClientType.LOCAL_ON_DEVICE -> 15L
    ClientType.OLLAMA -> 30L
    ClientType.LLAMACPP -> 30L
    else -> 30L
}

fun getPlatformReadTimeoutSeconds(platform: ClientType): Long = when (platform) {
    ClientType.LOCAL_ON_DEVICE -> 60L
    ClientType.OLLAMA -> 180L
    ClientType.LLAMACPP -> 180L
    else -> 60L
}
