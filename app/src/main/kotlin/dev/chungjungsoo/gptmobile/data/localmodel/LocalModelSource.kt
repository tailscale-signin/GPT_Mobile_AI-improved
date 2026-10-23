package dev.chungjungsoo.gptmobile.data.localmodel

import android.net.Uri

data class LocalModelSource(val uri: Uri, val kind: Kind, val mode: Mode = Mode.LINK) {
    enum class Mode { LINK, COPY }
    enum class Kind { FILE, FOLDER }
    val folder: Boolean get() = kind == Kind.FOLDER
}

/** Preserve an explicit copy choice queued by the earlier boolean-based worker input. */
internal fun localModelImportMode(mode: String?, legacyCopyToApp: Boolean): LocalModelSource.Mode =
    mode?.let(LocalModelSource.Mode::valueOf) ?: if (legacyCopyToApp) LocalModelSource.Mode.COPY else LocalModelSource.Mode.LINK
