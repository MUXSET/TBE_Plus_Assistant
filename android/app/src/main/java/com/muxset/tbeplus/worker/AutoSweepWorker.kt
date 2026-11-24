package com.muxset.tbeplus.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.muxset.tbeplus.MainActivity
import com.muxset.tbeplus.R
import com.muxset.tbeplus.data.AppRepository
import com.muxset.tbeplus.engine.SweepEngine
import com.muxset.tbeplus.engine.TbeaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AutoSweepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val CHANNEL_ID = "tbe_sweep_channel"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = AppRepository(applicationContext.filesDir)
        val cfg = repo.getConfigSnapshot()
        if (!cfg.autoBackgroundEnabled || cfg.accounts.isEmpty()) {
            return@withContext Result.success()
        }

        val client = TbeaClient()
        val engine = SweepEngine(repo, client)
        val today = LocalDate.now()
        val sd = today.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val ed = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val targets = if (cfg.multiAccountAuto) {
            cfg.accounts.map { it.username }.filter { it.isNotBlank() }
        } else {
            listOfNotNull(repo.getActiveUsername())
        }

        var totalLiked = 0
        var totalScanned = 0
        var anyExpired = false

        for (user in targets) {
            if (isStopped) break
            val outcome = engine.runSweep(
                startDate = sd,
                endDate = ed,
                dryRun = false,
                username = user,
                isCancelled = { isStopped },
            )
            if (outcome.authExpired) {
                anyExpired = true
            } else {
                totalLiked += outcome.liked
                totalScanned = maxOf(totalScanned, outcome.total)
            }
        }

        if (anyExpired) {
            sendNotification(
                title = "凭据已过期",
                content = "后台自动扫描检测到登录态失效，点击打开应用自动续期",
                notifyId = 1002,
            )
        } else if (totalLiked > 0) {
            sendNotification(
                title = "后台自动点赞完成",
                content = "新点赞 $totalLiked 篇文章（当月共 $totalScanned 篇）",
                notifyId = 1001,
            )
        }

        Result.success()
    }

    private fun sendNotification(title: String, content: String, notifyId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "自动阅赞通知",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        nm.createNotificationChannel(channel)

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext,
            notifyId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(notifyId, notif)
    }
}
