package dev.chungjungsoo.gptmobile.data.marketplace

/** Only fixed messages leave this boundary: causes can contain keys, endpoint queries or paths. */
internal fun marketplaceFailureMessage(error: Exception, removing: Boolean = false): String {
    val hint = error.message.orEmpty().lowercase()
    return when {
        error is MarketplaceRegistryRepairRequired -> "Plugin registry needs repair. Use Repair plugin registry before retrying."
        error is java.net.SocketTimeoutException || error is java.net.UnknownHostException -> "Provider could not be reached. Check your connection and retry."
        "integrity" in hint || "checksum" in hint || "package validation" in hint -> "Package verification failed. Retry setup to obtain a verified package."
        "space" in hint || "enospc" in hint || "storage" in hint -> "Not enough usable storage. Free space and retry."
        hint == "install this plugin first." -> "Add this integration in Marketplace before using it."
        hint == "this plugin is disabled." -> "This integration is disabled. Enable it in Marketplace."
        "daily request allowance" in hint -> "Daily request allowance reached. Review its usage limit or try after 00:00 UTC."
        "cooldown active" in hint -> "Provider cooldown active. Check Marketplace for the remaining wait."
        "api key is missing" in hint -> "Provider key is missing or invalid. Add it in plugin settings."
        "required plugin settings" in hint || "required fields" in hint -> "Setup is incomplete. Open plugin settings and complete the required fields."
        removing -> "Removal needs to finish. Retry uninstall; the saved removal request will resume when Marketplace opens."
        error is java.io.IOException -> "Package could not be transferred or saved. Check connectivity and available storage, then retry."
        else -> "Integration could not be updated. Review its settings and retry."
    }
}
