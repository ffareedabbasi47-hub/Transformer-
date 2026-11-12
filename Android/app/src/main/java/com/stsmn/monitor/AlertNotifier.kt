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

class AlertNotifier(context: Context) {
    private val app = context.applicationContext

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = app.getSystemService(NotificationManager::class.java)
            val alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            val ch = NotificationChannel(CHANNEL, "Emergency alerts (keeps ringing)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A transformer reports an EMERGENCY: rings until the fault is gone"
                enableVibration(true)
                setSound(alarmSound, android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            }
            nm?.createNotificationChannel(ch)
            nm?.createNotificationChannel(NotificationChannel(WARN_CHANNEL, "Warnings (stay until fixed)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A transformer reports a WARNING: shown until it is gone"
            })
        }
    }

    fun emergency(transformerId: String, risk: Int?, supplyCut: Boolean?, whatsappUrl: String? = null) {
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
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(open)
            .apply {
                if (whatsappUrl != null) addAction(0, "Send WhatsApp", PendingIntent.getActivity(
                    app, 1, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(whatsappUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            .build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT or android.app.Notification.FLAG_NO_CLEAR
        try { NotificationManagerCompat.from(app).notify(transformerId.hashCode(), n) } catch (e: SecurityException) {  }
    }

    fun warning(transformerId: String, risk: Int?) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(app, WARN_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Warning · STSMN-$transformerId".replace("STSMN-STSMN-", "STSMN-"))
            .setContentText("Risk score ${risk ?: "?"}/100. Stays until the fault is gone.")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false)
            .setContentIntent(openIntent())
            .build()
        try { NotificationManagerCompat.from(app).notify(WARN_BASE + transformerId.hashCode(), n) } catch (e: SecurityException) { }
    }

    fun clearEmergency(transformerId: String) { try { NotificationManagerCompat.from(app).cancel(transformerId.hashCode()) } catch (e: SecurityException) { } }
    fun clearWarning(transformerId: String) { try { NotificationManagerCompat.from(app).cancel(WARN_BASE + transformerId.hashCode()) } catch (e: SecurityException) { } }
    fun clearAll(transformerId: String) { clearEmergency(transformerId); clearWarning(transformerId) }

    private fun canPost() = !(Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)

    private fun openIntent() = PendingIntent.getActivity(
        app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object { const val CHANNEL = "stsmn_emergency_alarm"; const val WARN_CHANNEL = "stsmn_warning"; const val WARN_BASE = 1_000_000 }
}
