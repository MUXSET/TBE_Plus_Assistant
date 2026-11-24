package com.muxset.tbeplus.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

object WorkScheduler {
    private const val UNIQUE_WORK_NAME = "tbe_plus_auto_sweep"

    fun syncSchedule(context: Context, enabled: Boolean, intervalHours: Double) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            wm.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val intervalMinutes = (intervalHours * 60.0).roundToLong().coerceAtLeast(15L)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoSweepWorker>(
            intervalMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()

        wm.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
