package dev.chungjungsoo.gptmobile.data.localmodel

import android.net.Uri

data class LocalModelSource(val uri: Uri, val kind: Kind) {
    enum class Kind { FILE, FOLDER }
    val folder: Boolean get() = kind == Kind.FOLDER
}
