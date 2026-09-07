package dev.melo.gptmobile.improved.data.localmodel

enum class LocalModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    FAILED,
    CORRUPTED,
    CANCELED
}
