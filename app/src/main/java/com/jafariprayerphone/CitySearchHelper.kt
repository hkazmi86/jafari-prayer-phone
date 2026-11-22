package com.jafariprayerphone

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone

object CitySearchHelper {

    suspend fun searchLocations(context: Context, query: String): List<SavedCity> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        // 1. Primary: Open-Meteo Geocoding API (handles cities + postal codes + returns exact IANA timezone)
        val openMeteoResults = queryOpenMeteo(trimmed)
        if (openMeteoResults.isNotEmpty()) return@withContext openMeteoResults

        // 2. Fallback: Android Geocoder + Open-Meteo timezone resolution
        val fallback = mutableListOf<SavedCity>()
        try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.US).getFromLocationName(trimmed, 5) ?: emptyList()
            for (addr in addresses) {
                val label = buildList {
                    addr.locality?.let { add(it) } ?: addr.subAdminArea?.let { add(it) }
                    addr.adminArea?.let { add(it) }
                    addr.countryCode?.let { add(it) }
                }.distinct().joinToString(", ").ifEmpty { trimmed }
                val tz = fetchTimezoneForCoords(addr.latitude, addr.longitude) ?: TimeZone.getDefault().id
                fallback.add(SavedCity(label, addr.latitude, addr.longitude, tz))
            }
        } catch (_: Exception) {
        }
        fallback
    }

    suspend fun reverseGeocodeName(context: Context, lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val list = Geocoder(context, Locale.US).getFromLocation(lat, lng, 1)
            val addr = list?.firstOrNull()
            if (addr != null) {
                val parts = buildList {
                    addr.locality?.let { add(it) } ?: addr.subAdminArea?.let { add(it) }
                    addr.adminArea?.let { add(it) }
                }.distinct()
                if (parts.isNotEmpty()) return@withContext parts.joinToString(", ")
            }
        } catch (_: Exception) {
        }
        String.format(Locale.US, "%.2f, %.2f", lat, lng)
    }

    private fun queryOpenMeteo(query: String): List<SavedCity> {
        val results = mutableListOf<SavedCity>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=en&format=json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val arr = root.optJSONArray("results")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val name = o.optString("name", "")
                        val admin1 = o.optString("admin1", "")
                        val country = o.optString("country_code", o.optString("country", ""))
                        val label = listOf(name, admin1, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
                        val lat = o.getDouble("latitude")
                        val lng = o.getDouble("longitude")
                        val tz = o.optString("timezone", TimeZone.getDefault().id)
                        results.add(SavedCity(label, lat, lng, tz))
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        return results
    }

    private fun fetchTimezoneForCoords(lat: Double, lng: Double): String? {
        return try {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&timezone=auto&forecast_days=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                JSONObject(body).optString("timezone", null)
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
