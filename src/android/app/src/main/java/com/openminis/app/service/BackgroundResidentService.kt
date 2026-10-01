package com.openminis.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.data.repository.BackgroundSettingsRepository

/** User-requested resident session supervisor. No audio playback and no idle wake-lock/polling. */
class BackgroundResidentService : Service() {
    companion object {
        private const val CHANNEL = "minis_background_resident"
        private const val ID = 9003
        private const val STOP = "com.openminis.perf120.STOP_RESIDENT"
        fun sync(context: Context) {
            val enabled = BackgroundSettingsRepository(context).backgroundResidentEnabled.value
            if (!enabled) { context.stopService(Intent(context, BackgroundResidentService::class.java)); return }
            try {
                val intent = Intent(context, BackgroundResidentService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) { Log.w("ResidentService", "start declined: ${e.message}") }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repository = BackgroundSettingsRepository(this)
        if (intent?.action == STOP) repository.setBackgroundResidentEnabled(false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.bg_resident_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, ID, Intent(this, BackgroundResidentService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.bg_resident_title))
            .setContentText(getString(R.string.bg_resident_notification))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.bg_resident_stop), stop).build()
        // A startForegroundService request must be promoted even if it raced the off toggle.
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, notification)
        } catch (e: Exception) { Log.w("ResidentService", "foreground promotion failed", e); stopSelf(); return START_NOT_STICKY }
        if (!BackgroundRuntimePolicy.residentShouldRun(repository.backgroundResidentEnabled.value,
                com.openminis.app.crash.CrashFrequencyDetector.isSafeMode())) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
