package com.logie.gen1storage.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.logie.gen1storage.R

/**
 * Keeps a download alive while the player is somewhere else.
 *
 * Android freezes and then kills a process the moment it stops being the app
 * on screen, and a coroutine belonging to a view model goes with it — which
 * is why a sprite download used to stop dead as soon as the app was put
 * away. A foreground service is the one way to tell the system that work is
 * genuinely in progress, and the price of saying so is a notification the
 * player can see, which is fair: it is their battery and their data.
 *
 * The service does no downloading itself. The work runs in a scope that
 * outlives the screen; this only holds the process open and says how far
 * along it is.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val percent = intent?.getIntExtra(EXTRA_PERCENT, 0) ?: 0
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "DOWNLOADING"
        startForeground(NOTIFICATION_ID, notification(label, percent))
        // Not sticky: a process that died mid-download should not have the
        // system restart a service with nothing left running behind it.
        return START_NOT_STICKY
    }

    private fun notification(label: String, percent: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    // Low: it is a progress bar, not news.
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(label)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_PERCENT = "percent"
        private const val EXTRA_LABEL = "label"

        /**
         * Starts the service, or updates what it is saying. Best effort: a
         * device that refuses the service is a device the download runs on
         * anyway, for as long as it is allowed to.
         */
        fun show(context: Context, label: String, percent: Int) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_PERCENT, percent)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun hide(context: Context) {
            runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
        }
    }
}
