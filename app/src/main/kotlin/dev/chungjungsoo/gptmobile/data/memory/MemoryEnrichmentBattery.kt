package dev.chungjungsoo.gptmobile.data.memory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/** Null is unavailable evidence, never a claim that the battery constraint is satisfied. */
internal fun observeEnrichmentBatteryNotLow(context: Context): Flow<Boolean?> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_LOW -> trySend(false)
                Intent.ACTION_BATTERY_OKAY -> trySend(true)
            }
        }
    }
    var registered = false
    try {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        trySend(
            sticky?.let {
                initialEnrichmentBatteryNotLow(
                    it.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
                    it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                )
            }
        )
    } catch (_: RuntimeException) {
        trySend(null)
    }
    awaitClose { if (registered) context.unregisterReceiver(receiver) }
}.distinctUntilChanged().flowOn(Dispatchers.IO)
