package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.ClipData
import android.content.Intent
import android.net.Uri

/** Android sharesheet grants URI access only to the selected destination. */
internal fun chatExportChooser(uri: Uri, fileName: String, mimeType: String): Intent {
    require(uri.scheme == "content") { "Only content-provider exports may be shared." }
    // The export format already supplies its MIME type. ClipData.newUri resolves
    // it through FileProvider, which can load Android's MIME table from disk on
    // the UI thread the first time the sharesheet is opened.
    val clip = ClipData(fileName, arrayOf(mimeType), ClipData.Item(uri))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, "Share Chat Export").apply {
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
