package com.stsmn.monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** High-priority phone notification when any transformer enters EMERGENCY (mockup screen 10). */
class AlertNotifier(context: Context) {
    private val app = context.applicationContext

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Emergency alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A transformer reported an EMERGENCY"
                enableVibration(true)
            }
            app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    fun emergency(transformerId: String, risk: Int?, supplyCut: Boolean?) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = buildString {
            append("Risk score ").append(risk ?: "?").append("/100.")
            if (supplyCut == true) append(" Supply cut off automatically.")
        }
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Emergency Alert · STSMN-$transformerId".replace("STSMN-STSMN-", "STSMN-"))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(app).notify(transformerId.hashCode(), n) } catch (e: SecurityException) { /* permission revoked meanwhile */ }
    }

    private companion object { const val CHANNEL = "stsmn_emergency" }
}
