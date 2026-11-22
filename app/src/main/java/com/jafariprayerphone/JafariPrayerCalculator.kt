package com.jafariprayerphone

import android.icu.util.IslamicCalendar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class PrayerEntry(
    val key: String,
    val name: String,
    val epochMillis: Long
)

data class DayPrayers(
    val imsak: Long,
    val fajr: Long,
    val sunrise: Long,
    val dhuhr: Long,
    val asr: Long,
    val sunset: Long,
    val maghrib: Long,
    val isha: Long,
    val midnight: Long
) {
    fun asList(includeSeven: Boolean): List<PrayerEntry> {
        return if (includeSeven) {
            listOf(
                PrayerEntry("fajr", "Fajr", fajr),
                PrayerEntry("sunrise", "Sunrise", sunrise),
                PrayerEntry("dhuhr", "Dhuhr", dhuhr),
                PrayerEntry("asr", "Asr", asr),
                PrayerEntry("sunset", "Sunset", sunset),
                PrayerEntry("maghrib", "Maghrib", maghrib),
                PrayerEntry("isha", "Isha", isha)
            )
        } else {
            listOf(
                PrayerEntry("fajr", "Fajr", fajr),
                PrayerEntry("sunrise", "Sunrise", sunrise),
                PrayerEntry("dhuhr", "Dhuhr", dhuhr),
                PrayerEntry("sunset", "Sunset", sunset),
                PrayerEntry("maghrib", "Maghrib", maghrib)
            )
        }
    }
}

object JafariPrayerCalculator {

    const val KAABA_LAT = 21.422487
    const val KAABA_LNG = 39.826206

    private val HIJRI_MONTHS = arrayOf(
        "Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani",
        "Jumada al-Ula", "Jumada al-Akhirah", "Rajab", "Sha'ban",
        "Ramadan", "Shawwal", "Dhu al-Qi'dah", "Dhu al-Hijjah"
    )

    private fun dtr(d: Double): Double = d * Math.PI / 180.0
    private fun rtd(r: Double): Double = r * 180.0 / Math.PI
    private fun fixAngle(a: Double): Double = a - 360.0 * floor(a / 360.0)
    private fun fixHour(h: Double): Double = h - 24.0 * floor(h / 24.0)

    private fun julianDate(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2.0 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sin(dtr(g)) + 0.020 * sin(dtr(2.0 * g)))
        val e = 23.439 - 0.00000036 * d
        val ra = rtd(atan2(cos(dtr(e)) * sin(dtr(l)), cos(dtr(l)))) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = rtd(asin(sin(dtr(e)) * sin(dtr(l))))
        return Pair(decl, eqt)
    }

    private fun midDay(jd: Double, t: Double): Double {
        val (_, eqt) = sunPosition(jd + t)
        return fixHour(12.0 - eqt)
    }

    private fun sunAngleTime(
        jd: Double,
        lat: Double,
        angle: Double,
        t: Double,
        ccw: Boolean
    ): Double {
        val (decl, _) = sunPosition(jd + t)
        val noon = midDay(jd, t)
        val cosVal = ((-sin(dtr(angle)) - sin(dtr(lat)) * sin(dtr(decl))) /
                (cos(dtr(lat)) * cos(dtr(decl)))).coerceIn(-1.0, 1.0)
        val deltaT = rtd(acos(cosVal)) / 15.0
        return if (ccw) noon - deltaT else noon + deltaT
    }

    private fun asrTime(jd: Double, lat: Double, t: Double): Double {
        val (decl, _) = sunPosition(jd + t)
        val angle = -rtd(atan(1.0 / (1.0 + tan(abs(dtr(lat - decl))))))
        return sunAngleTime(jd, lat, angle, t, false)
    }

    private fun computeRawHours(
        year: Int,
        month: Int,
        day: Int,
        lat: Double,
        lng: Double,
        tzHours: Double,
        method: CalcMethod
    ): DoubleArray {
        val fajrAngle = when (method) {
            CalcMethod.LEVA -> 16.0
            CalcMethod.TEHRAN -> 17.7
        }
        val maghribAngle = when (method) {
            CalcMethod.LEVA -> 4.0
            CalcMethod.TEHRAN -> 4.5
        }
        val ishaAngle = 14.0

        val jd = julianDate(year, month, day) - lng / 360.0
        var fajr = 5.0 / 24.0
        var sunrise = 6.0 / 24.0
        var dhuhr = 12.0 / 24.0
        var asr = 13.0 / 24.0
        var sunset = 18.0 / 24.0
        var maghrib = 18.0 / 24.0
        var isha = 18.0 / 24.0

        repeat(2) {
            fajr = sunAngleTime(jd, lat, fajrAngle, fajr, true) / 24.0
            sunrise = sunAngleTime(jd, lat, 0.833, sunrise, true) / 24.0
            dhuhr = midDay(jd, dhuhr) / 24.0
            asr = asrTime(jd, lat, asr) / 24.0
            sunset = sunAngleTime(jd, lat, 0.833, sunset, false) / 24.0
            maghrib = sunAngleTime(jd, lat, maghribAngle, maghrib, false) / 24.0
            isha = sunAngleTime(jd, lat, ishaAngle, isha, false) / 24.0
        }

        val shift = tzHours - lng / 15.0
        return doubleArrayOf(
            fixHour(fajr * 24.0 + shift),
            fixHour(sunrise * 24.0 + shift),
            fixHour(dhuhr * 24.0 + shift),
            fixHour(asr * 24.0 + shift),
            fixHour(sunset * 24.0 + shift),
            fixHour(maghrib * 24.0 + shift),
            fixHour(isha * 24.0 + shift)
        )
    }

    fun calculateForDate(
        year: Int,
        monthZeroBased: Int,
        dayOfMonth: Int,
        lat: Double,
        lng: Double,
        tz: TimeZone,
        method: CalcMethod
    ): DayPrayers {
        val dayStart = Calendar.getInstance(tz).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val tzHours = tz.getOffset(dayStart.timeInMillis) / 3_600_000.0
        val today = computeRawHours(year, monthZeroBased + 1, dayOfMonth, lat, lng, tzHours, method)

        val tomorrowCal = (dayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTz = tz.getOffset(tomorrowCal.timeInMillis) / 3_600_000.0
        val tomorrow = computeRawHours(
            tomorrowCal.get(Calendar.YEAR),
            tomorrowCal.get(Calendar.MONTH) + 1,
            tomorrowCal.get(Calendar.DAY_OF_MONTH),
            lat,
            lng,
            tomorrowTz,
            method
        )

        fun hoursToMillis(baseCal: Calendar, hours: Double): Long {
            val totalMinutes = (hours * 60.0).roundToInt()
            return baseCal.timeInMillis + totalMinutes * 60_000L
        }

        val fajrMs = hoursToMillis(dayStart, today[0])
        val imsakMs = fajrMs - 10 * 60_000L
        val sunriseMs = hoursToMillis(dayStart, today[1])
        val dhuhrMs = hoursToMillis(dayStart, today[2])
        val asrMs = hoursToMillis(dayStart, today[3])
        val sunsetMs = hoursToMillis(dayStart, today[4])
        val maghribMs = hoursToMillis(dayStart, today[5])
        val ishaMs = hoursToMillis(dayStart, today[6])
        val nextFajrMs = hoursToMillis(tomorrowCal, tomorrow[0])
        val midnightMs = sunsetMs + (nextFajrMs - sunsetMs) / 2L

        return DayPrayers(
            imsak = imsakMs,
            fajr = fajrMs,
            sunrise = sunriseMs,
            dhuhr = dhuhrMs,
            asr = asrMs,
            sunset = sunsetMs,
            maghrib = maghribMs,
            isha = ishaMs,
            midnight = midnightMs
        )
    }

    fun formatTime(epochMillis: Long, tz: TimeZone, use24Hour: Boolean): String {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = epochMillis }
        val hour24 = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return if (use24Hour) {
            String.format(Locale.US, "%02d:%02d", hour24, minute)
        } else {
            val amPm = if (hour24 < 12) "AM" else "PM"
            val hour12 = when (val h = hour24 % 12) {
                0 -> 12
                else -> h
            }
            String.format(Locale.US, "%d:%02d %s", hour12, minute, amPm)
        }
    }

    fun formatCountdown(deltaMillis: Long): String {
        val totalMins = ((deltaMillis + 59_999L) / 60_000L).coerceAtLeast(1L)
        val hours = totalMins / 60L
        val mins = totalMins % 60L
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${mins}m"
        }
    }

    fun formatGregorianDate(cal: Calendar): String {
        val sdf = SimpleDateFormat("EEE, MMM d, yyyy", Locale.US)
        sdf.timeZone = cal.timeZone
        return sdf.format(cal.time)
    }

    fun formatHijriDate(cal: Calendar, offsetDays: Int): String {
        val ic = IslamicCalendar()
        ic.timeInMillis = cal.timeInMillis + offsetDays * 86_400_000L
        val day = ic.get(IslamicCalendar.DAY_OF_MONTH)
        val monthIdx = ic.get(IslamicCalendar.MONTH).coerceIn(0, 11)
        val year = ic.get(IslamicCalendar.YEAR)
        return "$day ${HIJRI_MONTHS[monthIdx]} $year AH"
    }

    fun qiblaBearingDegrees(lat: Double, lng: Double): Double {
        val phi1 = dtr(lat)
        val phi2 = dtr(KAABA_LAT)
        val dLambda = dtr(KAABA_LNG - lng)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (rtd(atan2(y, x)) + 360.0) % 360.0
    }

    fun distanceToKaabaKm(lat: Double, lng: Double): Double {
        val r = 6371.0
        val dLat = dtr(KAABA_LAT - lat)
        val dLng = dtr(KAABA_LNG - lng)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(dtr(lat)) * cos(dtr(KAABA_LAT)) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun compassCardinal(deg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((deg + 22.5) % 360.0) / 45.0).toInt().coerceIn(0, 7)
        return dirs[idx]
    }
}
