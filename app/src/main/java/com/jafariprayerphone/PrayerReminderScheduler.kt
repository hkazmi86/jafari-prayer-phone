package com.jafariprayerphone

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.Calendar

object PrayerReminderScheduler {
    const val CHANNEL_ID = "jafari_prayer_reminders"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Prayer Time Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for Ja'fari prayer times"
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    fun rescheduleAll(context: Context) {
        val prefs = PrayerPrefs(context)
        val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Cancel existing slots
        for (reqCode in 100..115) {
            val pi = PendingIntent.getBroadcast(
                context,
                reqCode,
                Intent(context, PrayerReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) alarmMgr.cancel(pi)
        }

        if (!prefs.hasLocation) return

        val tz = prefs.activeTimeZone()
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance(tz)
        val today = JafariPrayerCalculator.calculateForDate(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            prefs.latitude, prefs.longitude, tz, prefs.method
        )
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrow = JafariPrayerCalculator.calculateForDate(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            prefs.latitude, prefs.longitude, tz, prefs.method
        )

        // Schedule widget refresh at the very next prayer boundary even if notifications are off
        val allTimes = (today.asList(prefs.showSevenRows) + tomorrow.asList(prefs.showSevenRows))
            .map { it.epochMillis }
            .filter { it > now + 2000L }
            .sorted()
        allTimes.firstOrNull()?.let { nextBoundary ->
            val widgetPi = PendingIntent.getBroadcast(
                context,
                115,
                Intent(context, PrayerWidgetProvider::class.java).apply {
                    action = PrayerWidgetProvider.ACTION_REFRESH
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextBoundary + 1500L, widgetPi)
        }

        if (!prefs.notificationsEnabled) return
        ensureChannel(context)

        val candidates = listOf(
            Triple("fajr", "Fajr Prayer", today.fajr),
            Triple("sunrise_pre", "15 min until Sunrise", today.sunrise - 15 * 60_000L),
            Triple("dhuhr", "Dhuhr Prayer", today.dhuhr),
            Triple("asr", "Asr Prayer", today.asr),
            Triple("maghrib", "Maghrib Prayer", today.maghrib),
            Triple("isha", "Isha Prayer", today.isha),
            Triple("fajr", "Fajr Prayer", tomorrow.fajr),
            Triple("sunrise_pre", "15 min until Sunrise", tomorrow.sunrise - 15 * 60_000L),
            Triple("dhuhr", "Dhuhr Prayer", tomorrow.dhuhr),
            Triple("asr", "Asr Prayer", tomorrow.asr),
            Triple("maghrib", "Maghrib Prayer", tomorrow.maghrib),
            Triple("isha", "Isha Prayer", tomorrow.isha)
        )

        var code = 100
        for ((key, title, triggerMs) in candidates) {
            if (!prefs.isPrayerNotifEnabled(key)) continue
            if (triggerMs <= now + 1000L) continue
            val timeLabel = JafariPrayerCalculator.formatTime(triggerMs, tz, prefs.use24Hour)
            val intent = Intent(context, PrayerReminderReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("body", "${prefs.locationName} · $timeLabel (${prefs.method.shortLabel})")
                putExtra("notif_id", code)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmMgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
            code++
            if (code >= 114) break
        }
    }

    fun showNotification(context: Context, title: String, body: String, id: Int) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .build()
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(id, notif)
    }
}

class PrayerReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "Prayer Time"
        val body = intent.getStringExtra("body") ?: "Time for prayer"
        val id = intent.getIntExtra("notif_id", 200)
        PrayerReminderScheduler.showNotification(context, title, body, id)
        PrayerWidgetProvider.updateAllWidgets(context)
        PrayerReminderScheduler.rescheduleAll(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PrayerWidgetProvider.updateAllWidgets(context)
        PrayerReminderScheduler.rescheduleAll(context)
    }
}
