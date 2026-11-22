package com.jafariprayerphone

import android.Manifest
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var prefs: PrayerPrefs
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tickerHandler = Handler(Looper.getMainLooper())

    private var selectedDateCal: Calendar = Calendar.getInstance()
    private var activeTab: Int = 0 // 0=Times, 1=Qibla, 2=Cities, 3=Settings
    private var currentPhotoIndex: Int = 0

    // Background views
    private lateinit var bgImageView: ImageView
    private lateinit var bgScrimView: View
    private lateinit var contentFrame: FrameLayout
    private lateinit var navButtons: List<TextView>

    // Times tab dynamic refs
    private var timesCountdownView: TextView? = null
    private var timesRowsContainer: LinearLayout? = null
    private var timesFooterImsak: TextView? = null
    private var timesFooterMidnight: TextView? = null
    private var timesDateGreg: TextView? = null
    private var timesDateHijri: TextView? = null
    private var timesLocLabel: TextView? = null
    private var timesPhotoCaption: TextView? = null

    // Qibla compass refs
    private var sensorManager: SensorManager? = null
    private var qiblaHeadingView: TextView? = null
    private var qiblaWebView: WebView? = null
    private var currentQiblaBearing: Double = 0.0

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) fetchGpsLocation() else Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            prefs.notificationsEnabled = true
            PrayerReminderScheduler.rescheduleAll(this)
            renderCurrentTab()
        } else {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val minuteTicker = object : Runnable {
        override fun run() {
            if (activeTab == 0) updateTimesContent()
            tickerHandler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrayerPrefs(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        selectedDateCal = Calendar.getInstance(prefs.activeTimeZone())
        currentPhotoIndex = MasjidGallery.nextPhotoIndex(prefs)

        buildRootShell()
        loadBackgroundPhoto()

        if (!prefs.hasLocation) {
            requestOrFetchGps()
        } else {
            PrayerWidgetProvider.updateAllWidgets(this)
            PrayerReminderScheduler.rescheduleAll(this)
        }
        renderCurrentTab()
    }

    override fun onResume() {
        super.onResume()
        tickerHandler.post(minuteTicker)
        if (activeTab == 1) startCompass()
    }

    override fun onPause() {
        super.onPause()
        tickerHandler.removeCallbacks(minuteTicker)
        stopCompass()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun buildRootShell() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // Layer 1: Masjid Photo Background
        bgImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(bgImageView)

        // Layer 2: Dark Gradient Scrim for guaranteed legibility
        bgScrimView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#B8000000"),
                    Color.parseColor("#CC050B0A"),
                    Color.parseColor("#EE000000")
                )
            )
        }
        root.addView(bgScrimView)

        // Layer 3: Main Vertical Layout (Content + Bottom Nav)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        shell.addView(contentFrame)

        // Bottom Navigation Bar
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E60A0D0F"))
            setPadding(dp(8), dp(8), dp(8), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tabs = listOf("🕌\nTimes", "🧭\nQibla", "📍\nCities", "⚙\nSettings")
        navButtons = tabs.mapIndexed { idx, label ->
            TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4), dp(6), dp(4), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (activeTab != idx) {
                        if (activeTab == 1) stopCompass()
                        activeTab = idx
                        renderCurrentTab()
                    }
                }
            }.also { navBar.addView(it) }
        }
        shell.addView(navBar)
        root.addView(shell)
        setContentView(root)
    }

    private fun updateNavHighlights() {
        navButtons.forEachIndexed { idx, tv ->
            val selected = idx == activeTab
            tv.setTextColor(if (selected) Color.parseColor("#F5C542") else Color.parseColor("#8A949C"))
            tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun loadBackgroundPhoto() {
        if (!prefs.useMasjidBackgrounds) {
            bgImageView.setImageDrawable(null)
            bgImageView.setBackgroundColor(Color.BLACK)
            bgScrimView.visibility = View.GONE
            timesPhotoCaption?.visibility = View.GONE
            return
        }

        bgScrimView.visibility = View.VISIBLE
        // Rich dark emerald-midnight fallback gradient while photo loads
        bgImageView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#0D2620"),
                Color.parseColor("#071412"),
                Color.parseColor("#000000")
            )
        )

        val photo = MasjidGallery.photos[currentPhotoIndex]
        timesPhotoCaption?.apply {
            visibility = View.VISIBLE
            text = "🖼 ${photo.title} · ${photo.city}  (tap for next)"
        }

        uiScope.launch {
            val bmp = MasjidGallery.loadBitmap(this@MainActivity, photo)
            if (bmp != null && prefs.useMasjidBackgrounds) {
                bgImageView.setImageBitmap(bmp)
            }
        }
    }

    private fun renderCurrentTab() {
        updateNavHighlights()
        contentFrame.removeAllViews()
        when (activeTab) {
            0 -> buildTimesTab()
            1 -> buildQiblaTab()
            2 -> buildCitiesTab()
            3 -> buildSettingsTab()
        }
    }

    // =========================================================================
    // TAB 0: PRAYER TIMES
    // =========================================================================
    private fun buildTimesTab() {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(42), dp(20), dp(20))
        }

        // Top Bar: Location + Method badge
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timesLocLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                activeTab = 2
                renderCurrentTab()
            }
        }
        val methodPill = TextView(this).apply {
            text = prefs.method.shortLabel
            setTextColor(Color.parseColor("#69F0AE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = pillBg("#CC12352C", 14)
            setOnClickListener {
                prefs.method = if (prefs.method == CalcMethod.LEVA) CalcMethod.TEHRAN else CalcMethod.LEVA
                text = prefs.method.shortLabel
                updateTimesContent()
                PrayerWidgetProvider.updateAllWidgets(this@MainActivity)
                PrayerReminderScheduler.rescheduleAll(this@MainActivity)
            }
        }
        topRow.addView(timesLocLabel)
        topRow.addView(methodPill)
        col.addView(topRow)

        // Gregorian & Hijri Dates
        timesDateGreg = TextView(this).apply {
            setTextColor(Color.parseColor("#CFD8DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, dp(4), 0, 0)
        }
        col.addView(timesDateGreg)

        timesDateHijri = TextView(this).apply {
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(0, dp(2), 0, dp(12))
        }
        col.addView(timesDateHijri)

        // Date Navigation Row (< | Pick Date | Today | >)
        val dateNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        dateNav.addView(smallChip("◀") {
            selectedDateCal.add(Calendar.DAY_OF_YEAR, -1)
            updateTimesContent()
        })
        dateNav.addView(hSpace(8))
        dateNav.addView(smallChip("📅 Pick Date") {
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    selectedDateCal.set(Calendar.YEAR, y)
                    selectedDateCal.set(Calendar.MONTH, m)
                    selectedDateCal.set(Calendar.DAY_OF_MONTH, d)
                    updateTimesContent()
                },
                selectedDateCal.get(Calendar.YEAR),
                selectedDateCal.get(Calendar.MONTH),
                selectedDateCal.get(Calendar.DAY_OF_MONTH)
            ).show()
        })
        dateNav.addView(hSpace(8))
        dateNav.addView(smallChip("Today") {
            selectedDateCal = Calendar.getInstance(prefs.activeTimeZone())
            updateTimesContent()
        })
        dateNav.addView(hSpace(8))
        dateNav.addView(smallChip("▶") {
            selectedDateCal.add(Calendar.DAY_OF_YEAR, 1)
            updateTimesContent()
        })
        col.addView(dateNav)

        // Countdown Banner Card
        val countdownCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pillBg("#9912181B", 18, "#33F5C542")
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        timesCountdownView = TextView(this).apply {
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        countdownCard.addView(timesCountdownView)
        col.addView(countdownCard)

        // Prayer Rows Container Card
        timesRowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg("#99101417", 18, "#26FFFFFF")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        col.addView(timesRowsContainer)

        // Footer Card: Imsak & Shari'i Midnight
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pillBg("#99101417", 16, "#26FFFFFF")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
        timesFooterImsak = TextView(this).apply {
            setTextColor(Color.parseColor("#CFD8DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timesFooterMidnight = TextView(this).apply {
            setTextColor(Color.parseColor("#CFD8DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        footerRow.addView(timesFooterImsak)
        footerRow.addView(timesFooterMidnight)
        col.addView(footerRow)

        // Subtle Masjid Photo Caption (tap to cycle next photo)
        val photo = MasjidGallery.photos[currentPhotoIndex]
        timesPhotoCaption = TextView(this).apply {
            text = "🖼 ${photo.title} · ${photo.city}  (tap for next)"
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            gravity = Gravity.CENTER
            visibility = if (prefs.useMasjidBackgrounds) View.VISIBLE else View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setOnClickListener {
                currentPhotoIndex = MasjidGallery.nextPhotoIndex(prefs)
                loadBackgroundPhoto()
            }
        }
        col.addView(timesPhotoCaption)

        scroll.addView(col)
        contentFrame.addView(scroll)
        updateTimesContent()
    }

    private fun updateTimesContent() {
        val tz = prefs.activeTimeZone()
        selectedDateCal.timeZone = tz

        val locPrefix = if (prefs.useAutoGps) "📍 " else "🌍 "
        timesLocLabel?.text = "$locPrefix${prefs.locationName}"
        timesDateGreg?.text = JafariPrayerCalculator.formatGregorianDate(selectedDateCal)
        timesDateHijri?.text = JafariPrayerCalculator.formatHijriDate(selectedDateCal, prefs.hijriOffsetDays)

        val container = timesRowsContainer ?: return
        container.removeAllViews()

        if (!prefs.hasLocation) {
            timesCountdownView?.text = "Location Needed"
            val btn = createActionBtn("Enable GPS Location", "#12352C", "#69F0AE") {
                requestOrFetchGps()
            }
            container.addView(btn)
            timesFooterImsak?.text = "Imsak\n--"
            timesFooterMidnight?.text = "Shari'i Midnight\n--"
            return
        }

        val todayCal = Calendar.getInstance(tz)
        val isToday = selectedDateCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                selectedDateCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)

        val dayPrayers = JafariPrayerCalculator.calculateForDate(
            selectedDateCal.get(Calendar.YEAR),
            selectedDateCal.get(Calendar.MONTH),
            selectedDateCal.get(Calendar.DAY_OF_MONTH),
            prefs.latitude,
            prefs.longitude,
            tz,
            prefs.method
        )
        val entries = dayPrayers.asList(prefs.showSevenRows)
        val now = System.currentTimeMillis()

        val nextEntry: PrayerEntry? = if (isToday) {
            val upcoming = entries.firstOrNull { it.epochMillis > now }
            if (upcoming != null) {
                upcoming
            } else {
                val tom = (selectedDateCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                val tomPrayers = JafariPrayerCalculator.calculateForDate(
                    tom.get(Calendar.YEAR), tom.get(Calendar.MONTH), tom.get(Calendar.DAY_OF_MONTH),
                    prefs.latitude, prefs.longitude, tz, prefs.method
                )
                PrayerEntry("fajr", "Fajr", tomPrayers.fajr)
            }
        } else null

        if (isToday && nextEntry != null) {
            val cd = JafariPrayerCalculator.formatCountdown(nextEntry.epochMillis - now)
            timesCountdownView?.text = "${nextEntry.name} in $cd"
        } else {
            val tzShort = tz.getDisplayName(tz.inDaylightTime(selectedDateCal.time), TimeZone.SHORT, Locale.US)
            timesCountdownView?.text = "Schedule ($tzShort)"
        }

        for (e in entries) {
            val isNext = isToday && nextEntry != null && e.epochMillis == nextEntry.epochMillis
            val isPast = isToday && e.epochMillis <= now
            val color = when {
                isNext -> Color.parseColor("#69F0AE")
                isPast -> Color.parseColor("#7D858D")
                else -> Color.WHITE
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                if (isNext) {
                    background = pillBg("#D912352C", 12)
                }
            }
            val nameTv = TextView(this).apply {
                text = e.name
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = if (isNext) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeTv = TextView(this).apply {
                text = JafariPrayerCalculator.formatTime(e.epochMillis, tz, prefs.use24Hour)
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = if (isNext) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            row.addView(nameTv)
            row.addView(timeTv)
            container.addView(row)
        }

        val imsakStr = JafariPrayerCalculator.formatTime(dayPrayers.imsak, tz, prefs.use24Hour)
        val midStr = JafariPrayerCalculator.formatTime(dayPrayers.midnight, tz, prefs.use24Hour)
        timesFooterImsak?.text = "Imsak (Fasting)\n$imsakStr"
        timesFooterMidnight?.text = "Shari'i Midnight\n$midStr"
    }

    // =========================================================================
    // TAB 1: SATELLITE QIBLA FINDER
    // =========================================================================
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildQiblaTab() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(0, dp(36), 0, 0)
        }

        if (!prefs.hasLocation) {
            val msg = TextView(this).apply {
                text = "Set a GPS or city location first to view the Qibla satellite line."
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(80), dp(24), dp(24))
            }
            col.addView(msg)
            contentFrame.addView(col)
            return
        }

        val lat = prefs.latitude
        val lng = prefs.longitude
        currentQiblaBearing = JafariPrayerCalculator.qiblaBearingDegrees(lat, lng)
        val distKm = JafariPrayerCalculator.distanceToKaabaKm(lat, lng)
        val distMi = distKm * 0.621371
        val cardinal = JafariPrayerCalculator.compassCardinal(currentQiblaBearing)

        // Header info card
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg("#CC101417", 16, "#33F5C542")
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(14), dp(4), dp(14), dp(10))
            }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bearingTv = TextView(this).apply {
            text = String.format(Locale.US, "Qibla: %.1f° %s", currentQiblaBearing, cardinal)
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val recenterBtn = smallChip("⌖ Re-center") {
            qiblaWebView?.evaluateJavascript("recenterMap();", null)
        }
        titleRow.addView(bearingTv)
        titleRow.addView(recenterBtn)
        infoCard.addView(titleRow)

        val distTv = TextView(this).apply {
            text = String.format(Locale.US, "Distance to Kaaba: %,.0f mi (%,.0f km)", distMi, distKm)
            setTextColor(Color.parseColor("#CFD8DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(3), 0, dp(2))
        }
        infoCard.addView(distTv)

        qiblaHeadingView = TextView(this).apply {
            text = "Phone heading: calibrating compass..."
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }
        infoCard.addView(qiblaHeadingView)
        col.addView(infoCard)

        // Satellite Map WebView (Esri World Imagery + Leaflet geodesic line to Makkah)
        val web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = WebViewClient()
        }
        qiblaWebView = web

        val html = buildQiblaHtml(lat, lng)
        web.loadDataWithBaseURL("https://jafariprayer.local/", html, "text/html", "UTF-8", null)
        col.addView(web)

        contentFrame.addView(col)
        startCompass()
    }

    private fun buildQiblaHtml(lat: Double, lng: Double): String {
        return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; }
</style>
</head>
<body>
<div id="map"></div>
<script>
  const userLat = $lat;
  const userLng = $lng;
  const kaabaLat = 21.422487;
  const kaabaLng = 39.826206;

  const map = L.map('map', { zoomControl: true }).setView([userLat, userLng], 19);

  L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
    maxZoom: 20,
    attribution: 'Esri Satellite'
  }).addTo(map);

  // Compute Great-Circle (Geodesic) points from User -> Kaaba
  function toRad(d) { return d * Math.PI / 180; }
  function toDeg(r) { return r * 180 / Math.PI; }

  function geodesicPoints(lat1, lon1, lat2, lon2, steps) {
    const p1 = toRad(lat1), l1 = toRad(lon1);
    const p2 = toRad(lat2), l2 = toRad(lon2);
    const d = 2 * Math.asin(Math.sqrt(
      Math.pow(Math.sin((p2 - p1) / 2), 2) +
      Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin((l2 - l1) / 2), 2)
    ));
    const pts = [];
    for (let i = 0; i <= steps; i++) {
      const f = i / steps;
      const A = Math.sin((1 - f) * d) / Math.sin(d);
      const B = Math.sin(f * d) / Math.sin(d);
      const x = A * Math.cos(p1) * Math.cos(l1) + B * Math.cos(p2) * Math.cos(l2);
      const y = A * Math.cos(p1) * Math.sin(l1) + B * Math.cos(p2) * Math.sin(l2);
      const z = A * Math.sin(p1) + B * Math.sin(p2);
      const lat = toDeg(Math.atan2(z, Math.sqrt(x * x + y * y)));
      const lon = toDeg(Math.atan2(y, x));
      pts.push([lat, lon]);
    }
    return pts;
  }

  const linePts = geodesicPoints(userLat, userLng, kaabaLat, kaabaLng, 120);
  L.polyline(linePts, { color: '#F5C542', weight: 4, opacity: 0.95 }).addTo(map);

  L.circleMarker([userLat, userLng], {
    radius: 8, fillColor: '#69F0AE', color: '#000', weight: 2, fillOpacity: 1
  }).addTo(map).bindPopup('Your Location');

  L.circleMarker([kaabaLat, kaabaLng], {
    radius: 8, fillColor: '#F5C542', color: '#000', weight: 2, fillOpacity: 1
  }).addTo(map).bindPopup('Kaaba, Makkah');

  function recenterMap() {
    map.setView([userLat, userLng], 19);
  }
</script>
</body>
</html>
""".trimIndent()
    }

    private fun startCompass() {
        val sm = sensorManager ?: return
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rot != null) {
            sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopCompass() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            val azimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0)
            var diff = abs(azimuth - currentQiblaBearing)
            if (diff > 180.0) diff = 360.0 - diff
            val aligned = diff <= 5.0
            qiblaHeadingView?.apply {
                text = if (aligned) {
                    String.format(Locale.US, "✓ Aligned with Qibla (%.0f°)", azimuth)
                } else {
                    String.format(Locale.US, "Phone heading: %.0f° (turn %.0f°)", azimuth, diff)
                }
                setTextColor(if (aligned) Color.parseColor("#69F0AE") else Color.parseColor("#CFD8DC"))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =========================================================================
    // TAB 2: LOCATIONS & SAVED CITIES
    // =========================================================================
    private fun buildCitiesTab() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(24))
        }

        col.addView(sectionHeader("Location & Saved Cities"))

        col.addView(createActionBtn("📍 Use Auto GPS (Current Location)", "#12352C", "#69F0AE") {
            requestOrFetchGps()
        })

        // Search Box
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14); bottomMargin = dp(10) }
        }
        val searchInput = EditText(this).apply {
            hint = "City or ZIP (e.g. Dearborn, 22405)"
            setHintTextColor(Color.parseColor("#8A949C"))
            setTextColor(Color.WHITE)
            setSingleLine(true)
            background = pillBg("#CC1A2024", 14, "#33FFFFFF")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val resultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val searchBtn = createActionBtn("Search", "#F5C542", "#000000") {
            val q = searchInput.text.toString().trim()
            if (q.length < 2) return@createActionBtn
            resultsBox.removeAllViews()
            resultsBox.addView(TextView(this).apply {
                text = "Searching..."
                setTextColor(Color.parseColor("#B0BEC5"))
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
            uiScope.launch {
                val found = CitySearchHelper.searchLocations(this@MainActivity, q)
                resultsBox.removeAllViews()
                if (found.isEmpty()) {
                    resultsBox.addView(TextView(this@MainActivity).apply {
                        text = "No matching city or ZIP found."
                        setTextColor(Color.parseColor("#EF9A9A"))
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                    })
                } else {
                    for (city in found) {
                        resultsBox.addView(buildCityCard(city, isSaved = false))
                    }
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        searchRow.addView(searchInput)
        searchRow.addView(searchBtn)
        col.addView(searchRow)
        col.addView(resultsBox)

        // Saved Favorite Cities
        val savedHeader = TextView(this).apply {
            text = "Saved Favorite Cities"
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(8))
        }
        col.addView(savedHeader)

        val saved = prefs.getSavedCities()
        if (saved.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "Search a city above and tap ★ Save to pin it here for 1-tap switching."
                setTextColor(Color.parseColor("#8A949C"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        } else {
            for (c in saved) {
                col.addView(buildCityCard(c, isSaved = true))
            }
        }

        scroll.addView(col)
        contentFrame.addView(scroll)
    }

    private fun buildCityCard(city: SavedCity, isSaved: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg("#CC151A1E", 14, "#26FFFFFF")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                prefs.saveLocation(city.latitude, city.longitude, city.name, city.timeZoneId, autoGps = false)
                selectedDateCal = Calendar.getInstance(prefs.activeTimeZone())
                PrayerWidgetProvider.updateAllWidgets(this@MainActivity)
                PrayerReminderScheduler.rescheduleAll(this@MainActivity)
                Toast.makeText(this@MainActivity, "Switched to ${city.name}", Toast.LENGTH_SHORT).show()
                activeTab = 0
                renderCurrentTab()
            }
        }
        infoCol.addView(TextView(this).apply {
            text = city.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        infoCol.addView(TextView(this).apply {
            text = city.timeZoneId
            setTextColor(Color.parseColor("#8A949C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        })
        card.addView(infoCol)

        if (isSaved) {
            card.addView(smallChip("✕") {
                prefs.removeSavedCity(city.name)
                renderCurrentTab()
            })
        } else {
            card.addView(smallChip("★ Save") {
                prefs.addSavedCity(city)
                Toast.makeText(this, "Saved ${city.name}", Toast.LENGTH_SHORT).show()
                renderCurrentTab()
            })
        }
        return card
    }

    // =========================================================================
    // TAB 3: SETTINGS
    // =========================================================================
    private fun buildSettingsTab() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(28))
        }

        col.addView(sectionHeader("Settings"))

        // Calculation Method
        col.addView(settingToggleCard(
            title = "Calculation Method",
            subtitle = prefs.method.label,
            buttonText = "Switch"
        ) {
            prefs.method = if (prefs.method == CalcMethod.LEVA) CalcMethod.TEHRAN else CalcMethod.LEVA
            syncAndRedraw()
        })

        // 7 vs 5 rows
        col.addView(settingToggleCard(
            title = "Displayed Prayer Rows",
            subtitle = if (prefs.showSevenRows) "7 Rows (Fajr, Sunrise, Dhuhr, Asr, Sunset, Maghrib, Isha)" else "5 Rows (Fajr, Sunrise, Dhuhr, Sunset, Maghrib)",
            buttonText = if (prefs.showSevenRows) "7 Rows" else "5 Rows"
        ) {
            prefs.showSevenRows = !prefs.showSevenRows
            syncAndRedraw()
        })

        // 12h / 24h
        col.addView(settingToggleCard(
            title = "Clock Format",
            subtitle = if (prefs.use24Hour) "24-Hour (17:11)" else "12-Hour (5:11 PM)",
            buttonText = if (prefs.use24Hour) "24h" else "12h"
        ) {
            prefs.use24Hour = !prefs.use24Hour
            syncAndRedraw()
        })

        // Hijri Offset
        val hijriCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg("#CC151A1E", 14, "#26FFFFFF")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val hLabel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        hLabel.addView(TextView(this).apply {
            text = "Hijri Date Adjustment"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        val offsetSign = if (prefs.hijriOffsetDays > 0) "+${prefs.hijriOffsetDays}" else "${prefs.hijriOffsetDays}"
        hLabel.addView(TextView(this).apply {
            text = "Offset: $offsetSign day(s)"
            setTextColor(Color.parseColor("#8A949C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        hijriCard.addView(hLabel)
        hijriCard.addView(smallChip("−") {
            prefs.hijriOffsetDays = (prefs.hijriOffsetDays - 1).coerceAtLeast(-2)
            renderCurrentTab()
        })
        hijriCard.addView(hSpace(8))
        hijriCard.addView(smallChip("+") {
            prefs.hijriOffsetDays = (prefs.hijriOffsetDays + 1).coerceAtMost(2)
            renderCurrentTab()
        })
        col.addView(hijriCard)

        // Background Photos Toggle
        col.addView(settingToggleCard(
            title = "App Background",
            subtitle = if (prefs.useMasjidBackgrounds) "Rotating Masjid Photos" else "AMOLED Pure Black",
            buttonText = if (prefs.useMasjidBackgrounds) "Photos" else "Black"
        ) {
            prefs.useMasjidBackgrounds = !prefs.useMasjidBackgrounds
            loadBackgroundPhoto()
            renderCurrentTab()
        })

        // Widget Opacity Slider
        val opacityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg("#CC151A1E", 14, "#26FFFFFF")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val opLabel = TextView(this).apply {
            text = "Home Screen Widget Opacity: ${prefs.widgetOpacityPercent}%"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val opHint = TextView(this).apply {
            text = "0% = Fully Transparent · 100% = Solid Black"
            setTextColor(Color.parseColor("#8A949C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, dp(2), 0, dp(8))
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = prefs.widgetOpacityPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    opLabel.text = "Home Screen Widget Opacity: $value%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.widgetOpacityPercent = sb?.progress ?: 75
                    PrayerWidgetProvider.updateAllWidgets(this@MainActivity)
                }
            })
        }
        opacityCard.addView(opLabel)
        opacityCard.addView(opHint)
        opacityCard.addView(seek)
        col.addView(opacityCard)

        // Notifications Section
        val notifCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg("#CC151A1E", 14, "#26FFFFFF")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val masterSwitch = Switch(this).apply {
            text = "Prayer Time Reminders"
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            isChecked = prefs.notificationsEnabled
            setOnCheckedChangeListener { _, checked ->
                if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    isChecked = false
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    prefs.notificationsEnabled = checked
                    PrayerReminderScheduler.rescheduleAll(this@MainActivity)
                    renderCurrentTab()
                }
            }
        }
        notifCard.addView(masterSwitch)

        if (prefs.notificationsEnabled) {
            val notifItems = listOf(
                "fajr" to "Fajr",
                "sunrise_pre" to "15 min before Sunrise",
                "dhuhr" to "Dhuhr",
                "asr" to "Asr",
                "maghrib" to "Maghrib",
                "isha" to "Isha"
            )
            for ((key, label) in notifItems) {
                val sw = Switch(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setPadding(0, dp(6), 0, dp(6))
                    isChecked = prefs.isPrayerNotifEnabled(key)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.setPrayerNotifEnabled(key, checked)
                        PrayerReminderScheduler.rescheduleAll(this@MainActivity)
                    }
                }
                notifCard.addView(sw)
            }
            notifCard.addView(createActionBtn("🔔 Send Test Notification", "#12352C", "#69F0AE") {
                PrayerReminderScheduler.showNotification(
                    this, "Ja'fari Prayer Reminder", "Notifications are working!", 999
                )
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })
        }
        col.addView(notifCard)

        scroll.addView(col)
        contentFrame.addView(scroll)
    }

    private fun syncAndRedraw() {
        PrayerWidgetProvider.updateAllWidgets(this)
        PrayerReminderScheduler.rescheduleAll(this)
        renderCurrentTab()
    }

    // =========================================================================
    // GPS HELPERS
    // =========================================================================
    private fun requestOrFetchGps() {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            fetchGpsLocation()
        } else {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchGpsLocation() {
        val client = LocationServices.getFusedLocationProviderClient(this)
        val cts = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    applyGpsCoords(loc.latitude, loc.longitude)
                } else {
                    client.lastLocation.addOnSuccessListener { last ->
                        if (last != null) {
                            applyGpsCoords(last.latitude, last.longitude)
                        } else {
                            Toast.makeText(this, "Could not get GPS fix yet", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "GPS error — try searching your city", Toast.LENGTH_SHORT).show()
            }
    }

    private fun applyGpsCoords(lat: Double, lng: Double) {
        uiScope.launch {
            val name = CitySearchHelper.reverseGeocodeName(this@MainActivity, lat, lng)
            prefs.saveLocation(lat, lng, name, TimeZone.getDefault().id, autoGps = true)
            selectedDateCal = Calendar.getInstance(prefs.activeTimeZone())
            PrayerWidgetProvider.updateAllWidgets(this@MainActivity)
            PrayerReminderScheduler.rescheduleAll(this@MainActivity)
            if (activeTab == 2) activeTab = 0
            renderCurrentTab()
        }
    }

    // =========================================================================
    // UI HELPERS
    // =========================================================================
    private fun sectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(14))
        }
    }

    private fun settingToggleCard(
        title: String,
        subtitle: String,
        buttonText: String,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg("#CC151A1E", 14, "#26FFFFFF")
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#8A949C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), dp(8), 0)
        })
        card.addView(textCol)
        card.addView(smallChip(buttonText, onClick))
        return card
    }

    private fun smallChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            background = pillBg("#CC222A30", 12, "#33FFFFFF")
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onClick() }
        }
    }

    private fun createActionBtn(label: String, bgHex: String, textHex: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor(textHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            background = pillBg(bgHex, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }
    }

    private fun pillBg(colorHex: String, radiusDp: Int, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeHex != null) {
                setStroke(dp(1), Color.parseColor(strokeHex))
            }
        }
    }

    private fun hSpace(dpVal: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(dpVal), 1)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
