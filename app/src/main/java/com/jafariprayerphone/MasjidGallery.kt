package com.jafariprayerphone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class MasjidPhoto(
    val id: String,
    val title: String,
    val city: String,
    val url: String
)

object MasjidGallery {

    val photos = listOf(
        MasjidPhoto(
            "najaf_hd",
            "Imam Ali Shrine",
            "Najaf, Iraq",
            "https://commons.wikimedia.org/wiki/Special:FilePath/Shrine_of_Imam_Ali_Najaf_August_2023.jpg?width=1200"
        ),
        MasjidPhoto(
            "karbala_hd",
            "Imam Husayn Shrine",
            "Karbala, Iraq",
            "https://commons.wikimedia.org/wiki/Special:FilePath/The_shrine_of_Imam_Hussain_in_Karbala.jpg?width=1200"
        ),
        MasjidPhoto(
            "mashhad",
            "Imam Reza Shrine",
            "Mashhad, Iran",
            "https://commons.wikimedia.org/wiki/Special:FilePath/Imam_Reza_shrine.jpg?width=1200"
        ),
        MasjidPhoto(
            "qom",
            "Fatima Masumeh Shrine",
            "Qom, Iran",
            "https://commons.wikimedia.org/wiki/Special:FilePath/Fatima_Masumeh_Shrine,_Qom,_Iran.jpg?width=1200"
        ),
        MasjidPhoto(
            "makkah",
            "Masjid al-Haram",
            "Makkah, Saudi Arabia",
            "https://commons.wikimedia.org/wiki/Special:FilePath/Masjid_al-Haram.JPG?width=1200"
        ),
        MasjidPhoto(
            "madinah",
            "Masjid an-Nabawi",
            "Madinah, Saudi Arabia",
            "https://commons.wikimedia.org/wiki/Special:FilePath/Al-Masjid_al-Nabawi.jpg?width=1200"
        ),
        MasjidPhoto(
            "shiraz",
            "Nasir al-Mulk Mosque",
            "Shiraz, Iran",
            "https://commons.wikimedia.org/wiki/Special:FilePath/Nasir_al-mulk_mosque_1.jpg?width=1200"
        ),
        MasjidPhoto(
            "abudhabi",
            "Sheikh Zayed Grand Mosque",
            "Abu Dhabi, UAE",
            "https://commons.wikimedia.org/wiki/Special:FilePath/Sheikh_Zayed_Grand_Mosque_in_Abu_Dhabi_-_panoramio.jpg?width=1200"
        ),
        MasjidPhoto(
            "istanbul",
            "Sultan Ahmed (Blue) Mosque",
            "Istanbul, Turkey",
            "https://commons.wikimedia.org/wiki/Special:FilePath/Blue_Mosque_-_Istanbul.jpg?width=1200"
        )
    )

    fun nextPhotoIndex(prefs: PrayerPrefs): Int {
        val next = (prefs.lastMasjidIndex + 1) % photos.size
        prefs.lastMasjidIndex = next
        return next
    }

    suspend fun loadBitmap(context: Context, photo: MasjidPhoto): Bitmap? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, "masjid_cache").apply { mkdirs() }
        val file = File(cacheDir, "${photo.id}.jpg")

        if (file.exists() && file.length() > 10_000L) {
            val cached = BitmapFactory.decodeFile(file.absolutePath)
            if (cached != null) return@withContext cached
        }

        try {
            var currentUrl = photo.url
            var redirects = 0
            var conn: HttpURLConnection
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "JafariPrayerApp/1.0 (Android; Personal)")
                }
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    currentUrl = loc
                    redirects++
                } else {
                    break
                }
            }

            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    FileOutputStream(file).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    }
                    return@withContext bmp
                }
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
        null
    }
}
