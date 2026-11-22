package com.jafariprayerphone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

enum class CalcMethod(val id: String, val label: String, val shortLabel: String) {
    LEVA("leva", "Leva Research Institute (Qom)", "Leva · Qom"),
    TEHRAN("tehran", "University of Tehran", "Tehran");

    companion object {
        fun fromId(id: String?): CalcMethod = entries.firstOrNull { it.id == id } ?: LEVA
    }
}

data class SavedCity(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timeZoneId: String
)

class PrayerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("jafari_phone_prefs", Context.MODE_PRIVATE)

    var method: CalcMethod
        get() = CalcMethod.fromId(prefs.getString(KEY_METHOD, CalcMethod.LEVA.id))
        set(value) = prefs.edit().putString(KEY_METHOD, value.id).apply()

    // Default to 7 rows as requested
    var showSevenRows: Boolean
        get() = prefs.getBoolean(KEY_SEVEN_ROWS, true)
        set(value) = prefs.edit().putBoolean(KEY_SEVEN_ROWS, value).apply()

    var use24Hour: Boolean
        get() = prefs.getBoolean(KEY_24_HOUR, false)
        set(value) = prefs.edit().putBoolean(KEY_24_HOUR, value).apply()

    var hijriOffsetDays: Int
        get() = prefs.getInt(KEY_HIJRI_OFFSET, 0)
        set(value) = prefs.edit().putInt(KEY_HIJRI_OFFSET, value.coerceIn(-2, 2)).apply()

    // 0 to 100 (default 75% opacity = sleek dark glass translucency)
    var widgetOpacityPercent: Int
        get() = prefs.getInt(KEY_WIDGET_OPACITY, 75)
        set(value) = prefs.edit().putInt(KEY_WIDGET_OPACITY, value.coerceIn(0, 100)).apply()

    var useMasjidBackgrounds: Boolean
        get() = prefs.getBoolean(KEY_MASJID_BG, true)
        set(value) = prefs.edit().putBoolean(KEY_MASJID_BG, value).apply()

    var lastMasjidIndex: Int
        get() = prefs.getInt(KEY_LAST_MASJID_IDX, -1)
        set(value) = prefs.edit().putInt(KEY_LAST_MASJID_IDX, value).apply()

    var useAutoGps: Boolean
        get() = prefs.getBoolean(KEY_USE_AUTO_GPS, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_AUTO_GPS, value).apply()

    var hasLocation: Boolean
        get() = prefs.getBoolean(KEY_HAS_LOC, false)
        private set(value) = prefs.edit().putBoolean(KEY_HAS_LOC, value).apply()

    var latitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_LAT, 0L))
        private set(value) = prefs.edit().putLong(KEY_LAT, value.toRawBits()).apply()

    var longitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_LNG, 0L))
        private set(value) = prefs.edit().putLong(KEY_LNG, value.toRawBits()).apply()

    var locationName: String
        get() = prefs.getString(KEY_LOC_NAME, "GPS Location") ?: "GPS Location"
        private set(value) = prefs.edit().putString(KEY_LOC_NAME, value).apply()

    var timeZoneId: String
        get() = prefs.getString(KEY_TZ_ID, TimeZone.getDefault().id) ?: TimeZone.getDefault().id
        private set(value) = prefs.edit().putString(KEY_TZ_ID, value).apply()

    fun activeTimeZone(): TimeZone {
        return if (useAutoGps) TimeZone.getDefault() else TimeZone.getTimeZone(timeZoneId)
    }

    fun saveLocation(lat: Double, lng: Double, name: String, tzId: String, autoGps: Boolean) {
        prefs.edit()
            .putBoolean(KEY_HAS_LOC, true)
            .putLong(KEY_LAT, lat.toRawBits())
            .putLong(KEY_LNG, lng.toRawBits())
            .putString(KEY_LOC_NAME, name)
            .putString(KEY_TZ_ID, tzId)
            .putBoolean(KEY_USE_AUTO_GPS, autoGps)
            .apply()
    }

    // Notification preferences
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_MASTER, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_MASTER, value).apply()

    fun isPrayerNotifEnabled(key: String): Boolean {
        val defaultOn = key in setOf("fajr", "dhuhr", "maghrib")
        return prefs.getBoolean("notif_$key", defaultOn)
    }

    fun setPrayerNotifEnabled(key: String, enabled: Boolean) {
        prefs.edit().putBoolean("notif_$key", enabled).apply()
    }

    // Saved Favorite Cities
    fun getSavedCities(): MutableList<SavedCity> {
        val raw = prefs.getString(KEY_SAVED_CITIES, "[]") ?: "[]"
        val list = mutableListOf<SavedCity>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    SavedCity(
                        name = o.getString("name"),
                        latitude = o.getDouble("lat"),
                        longitude = o.getDouble("lng"),
                        timeZoneId = o.getString("tz")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun addSavedCity(city: SavedCity) {
        val list = getSavedCities()
        if (list.none { it.name.equals(city.name, ignoreCase = true) }) {
            list.add(0, city)
            persistCities(list)
        }
    }

    fun removeSavedCity(name: String) {
        val list = getSavedCities().filterNot { it.name.equals(name, ignoreCase = true) }
        persistCities(list)
    }

    private fun persistCities(list: List<SavedCity>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject().apply {
                    put("name", c.name)
                    put("lat", c.latitude)
                    put("lng", c.longitude)
                    put("tz", c.timeZoneId)
                }
            )
        }
        prefs.edit().putString(KEY_SAVED_CITIES, arr.toString()).apply()
    }

    companion object {
        private const val KEY_METHOD = "calc_method"
        private const val KEY_SEVEN_ROWS = "show_seven_rows"
        private const val KEY_24_HOUR = "use_24_hour"
        private const val KEY_HIJRI_OFFSET = "hijri_offset"
        private const val KEY_WIDGET_OPACITY = "widget_opacity"
        private const val KEY_MASJID_BG = "masjid_bg"
        private const val KEY_LAST_MASJID_IDX = "last_masjid_idx"
        private const val KEY_USE_AUTO_GPS = "use_auto_gps"
        private const val KEY_HAS_LOC = "has_location"
        private const val KEY_LAT = "latitude"
        private const val KEY_LNG = "longitude"
        private const val KEY_LOC_NAME = "location_name"
        private const val KEY_TZ_ID = "timezone_id"
        private const val KEY_NOTIF_MASTER = "notif_master"
        private const val KEY_SAVED_CITIES = "saved_cities"
    }
}
