package com.tyranor.next.core.translation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController

/** Foreground notification for the app-wide translation content-task drain. */
internal object TranslationTaskNotification {
    private const val CHANNEL_ID = "translation_workbench_tasks"
    private const val NOTIFICATION_ID = 0x54594F

    fun createChannel(context: Context) {
        val localizedContext = AppLocaleController.wrap(context)
        val channel = NotificationChannel(
            CHANNEL_ID,
            localizedContext.getString(R.string.translation_task_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localizedContext.getString(R.string.translation_task_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun foregroundInfo(context: Context, completedFiles: Int, totalFiles: Int): ForegroundInfo {
        createChannel(context)
        val localizedContext = AppLocaleController.wrap(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(localizedContext.getString(R.string.translation_task_notification_title))
            .setContentText(localizedContext.getString(R.string.translation_task_notification_body))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(totalFiles.coerceAtLeast(0), completedFiles.coerceAtLeast(0), totalFiles <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
