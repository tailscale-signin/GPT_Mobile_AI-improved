package dev.melo.gptmobile.improved.presentation.ui.localmodel

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.melo.gptmobile.improved.data.catalog.CatalogEntry
import kotlin.math.roundToInt

class LocalDownloadGuards(
    private val context: Context
) {
    fun belowRamRequirement(entry: CatalogEntry): Boolean {
        val memoryInfo = ActivityManager.MemoryInfo().also {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.getMemoryInfo(it)
        }
        val totalRamGb = memoryInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val roundedTotalRamGb = totalRamGb.roundToInt()
        return roundedTotalRamGb < entry.minRamGb
    }

    fun isMeteredConnection(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (cm.isActiveNetworkMetered) return true
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        val isNotMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return !isNotMetered
    }
}
