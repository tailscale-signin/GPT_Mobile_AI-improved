package dev.chungjungsoo.gptmobile.data.pricing

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.concurrent.TimeUnit

@HiltWorker
class PricingRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingRepository,
    private val pricing: ProviderPricingRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
        val policy = settings.getFeatureSettings()
        if (policy.newChatPrices && policy.newChatPriceRefresh && policy.newChatBackgroundPrices) pricing.refresh(settings.fetchPlatformV2s())
        return Result.success()
    }
}

object PricingRefreshSchedule {
    fun reconcile(context: Context, settings: AppFeatureSettings, eligible: Boolean = true) {
        val manager = WorkManager.getInstance(context)
        if (!eligible || !settings.newChatPrices || !settings.newChatPriceRefresh || !settings.newChatBackgroundPrices) {
            manager.cancelUniqueWork("provider-price-refresh")
        } else {
            manager.enqueueUniquePeriodicWork(
                "provider-price-refresh",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PricingRefreshWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                    .build()
            )
        }
    }
}
