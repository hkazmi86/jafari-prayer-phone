package com.jafariprayerphone

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar

class PrayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, id)
        }
        PrayerReminderScheduler.rescheduleAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            updateAllWidgets(context)
            PrayerReminderScheduler.rescheduleAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.jafariprayerphone.ACTION_REFRESH_WIDGET"

        private val ROW_IDS = intArrayOf(
            R.id.w_row_0, R.id.w_row_1, R.id.w_row_2, R.id.w_row_3,
            R.id.w_row_4, R.id.w_row_5, R.id.w_row_6
        )
        private val NAME_IDS = intArrayOf(
            R.id.w_name_0, R.id.w_name_1, R.id.w_name_2, R.id.w_name_3,
            R.id.w_name_4, R.id.w_name_5, R.id.w_name_6
        )
        private val TIME_IDS = intArrayOf(
            R.id.w_time_0, R.id.w_time_1, R.id.w_time_2, R.id.w_time_3,
            R.id.w_time_4, R.id.w_time_5, R.id.w_time_6
        )

        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, PrayerWidgetProvider::class.java))
            for (id in ids) {
                updateSingleWidget(context, mgr, id)
            }
        }

        private fun updateSingleWidget(
            context: Context,
            mgr: AppWidgetManager,
            widgetId: Int
        ) {
            val prefs = PrayerPrefs(context)
            val views = RemoteViews(context.packageName, R.layout.widget_prayer)

            val alpha255 = (prefs.widgetOpacityPercent * 255 / 100).coerceIn(0, 255)
            views.setInt(R.id.widget_bg, "setImageAlpha", alpha255)

            val openAppPi = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPi)

            val refreshPi = PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, PrayerWidgetProvider::class.java).apply { action = ACTION_REFRESH },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPi)

            val tz = prefs.activeTimeZone()
            val todayCal = Calendar.getInstance(tz)
            val hijriStr = JafariPrayerCalculator.formatHijriDate(todayCal, prefs.hijriOffsetDays)
            views.setTextViewText(R.id.widget_hijri, hijriStr)

            if (!prefs.hasLocation) {
                views.setTextViewText(R.id.widget_countdown, "Tap to set location")
                views.setTextViewText(R.id.widget_location, "")
                for (i in 0 until 7) {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                }
                mgr.updateAppWidget(widgetId, views)
                return
            }

            val now = System.currentTimeMillis()
            val today = JafariPrayerCalculator.calculateForDate(
                todayCal.get(Calendar.YEAR), todayCal.get(Calendar.MONTH), todayCal.get(Calendar.DAY_OF_MONTH),
                prefs.latitude, prefs.longitude, tz, prefs.method
            )
            val tomorrowCal = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val tomorrow = JafariPrayerCalculator.calculateForDate(
                tomorrowCal.get(Calendar.YEAR), tomorrowCal.get(Calendar.MONTH), tomorrowCal.get(Calendar.DAY_OF_MONTH),
                prefs.latitude, prefs.longitude, tz, prefs.method
            )

            val entries = today.asList(prefs.showSevenRows)
            val nextEntry = entries.firstOrNull { it.epochMillis > now }
                ?: PrayerEntry("fajr", "Fajr", tomorrow.fajr)
            val countdown = JafariPrayerCalculator.formatCountdown(nextEntry.epochMillis - now)

            views.setTextViewText(R.id.widget_countdown, "${nextEntry.name} in $countdown")
            val shortLoc = prefs.locationName.substringBefore(",").take(14)
            views.setTextViewText(R.id.widget_location, shortLoc)

            for (i in 0 until 7) {
                if (i < entries.size) {
                    val e = entries[i]
                    val isNext = e.epochMillis == nextEntry.epochMillis
                    val isPast = e.epochMillis <= now
                    val color = when {
                        isNext -> Color.parseColor("#69F0AE")
                        isPast -> Color.WHITE
                        else -> Color.WHITE
                    }
                    views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                    views.setTextViewText(NAME_IDS[i], e.name)
                    views.setTextViewText(
                        TIME_IDS[i],
                        JafariPrayerCalculator.formatTime(e.epochMillis, tz, prefs.use24Hour)
                    )
                    views.setTextColor(NAME_IDS[i], color)
                    views.setTextColor(TIME_IDS[i], color)
                    views.setInt(
                        ROW_IDS[i],
                        "setBackgroundResource",
                        if (isNext) R.drawable.widget_highlight_bg else 0
                    )
                } else {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                }
            }

            mgr.updateAppWidget(widgetId, views)
        }
    }
}
