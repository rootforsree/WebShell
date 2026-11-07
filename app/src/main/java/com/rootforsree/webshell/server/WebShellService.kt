package com.rootforsree.webshell.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.rootforsree.webshell.MainActivity
import com.rootforsree.webshell.R
import com.rootforsree.webshell.WebShellApp

/**
 * Foreground service: the only reason the server keeps running when
 * the app leaves the foreground. It holds no server state itself; the
 * [ServerHost] owns the server, and this service only shows the
 * ongoing notification and offers a Stop action.
 *
 * The notification never shows the token or the full link: the token
 * lives in the app UI only.
 */
class WebShellService : Service() {
    private val host: ServerHost
        get() = (application as WebShellApp).serverHost

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_STOP -> {
                host.stop()
                stopSelf()
            }
            else -> {
                // Restarted by the system with a null intent: if nothing
                // is running anymore, there is nothing to keep alive.
                if (!host.isRunning.value) stopSelf()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = CHANNEL_ID
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "WebShell server", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while the web shell server is running."
                },
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WebShellService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle("WebShell running")
            .setContentText("Open the app for the web shell link.")
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_terminal, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.rootforsree.webshell.START"
        const val ACTION_STOP = "com.rootforsree.webshell.STOP"
        private const val CHANNEL_ID = "webshell"
        private const val NOTIFICATION_ID = 1
    }
}
