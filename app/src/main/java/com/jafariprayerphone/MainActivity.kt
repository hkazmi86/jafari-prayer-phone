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
import android.webkit.JavascriptInterface
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

    // Qibla compass & map refs
    private var sensorManager: SensorManager? = null
    private var qiblaBearingTv: TextView? = null
    private var qiblaDistTv: TextView? = null
    private var qiblaHeadingView: TextView? = null
    private var qiblaLayerBtn: TextView? = null
    private var qiblaWebView: WebView? = null
    private var currentQiblaBearing: Double = 0.0
    private var isSatelliteMode: Boolean = true
    private var qiblaPageLoaded: Boolean = false
    private var pendingQiblaCenter: Pair<Double, Double>? = null

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) {
            if (activeTab == 1) centerQiblaOnLiveGps() else fetchGpsLocation()
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
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

        // Layer 2: Light Gradient Scrim so Masjid photos shine clearly while text stays legible
        bgScrimView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#33000000"),
                    Color.parseColor("#4D000000"),
                    Color.parseColor("#7A000000")
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
            setBackgroundColor(Color.parseColor("#D90A0D0F"))
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
            tv.setTextColor(if (selected) Color.parseColor("#F5C542") else Color.parseColor("#9BA6AE"))
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
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
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
            background = pillBg("#A612352C", 14, "#4069F0AE")
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
            setTextColor(Color.parseColor("#EAEFF2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setShadowLayer(5f, 0f, 1f, Color.BLACK)
            setPadding(0, dp(4), 0, 0)
        }
        col.addView(timesDateGreg)

        timesDateHijri = TextView(this).apply {
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(5f, 0f, 1f, Color.BLACK)
            setPadding(0, dp(2), 0, dp(12))
        }
        col.addView(timesDateHijri)

        // Date Navigation Row
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

        // Frosted Glass Countdown Banner Card
        val countdownCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pillBg("#590A0F12", 18, "#45F5C542")
            setPadding(dp(16), dp(13), dp(16), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        timesCountdownView = TextView(this).apply {
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
        }
        countdownCard.addView(timesCountdownView)
        col.addView(countdownCard)

        // Frosted Glass Prayer Rows Container Card
        timesRowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg("#590A0F12", 18, "#38FFFFFF")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        col.addView(timesRowsContainer)

        // Footer Card: Imsak & Shari'i Midnight
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pillBg("#590A0F12", 16, "#38FFFFFF")
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        timesFooterImsak = TextView(this).apply {
            setTextColor(Color.parseColor("#EAEFF2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timesFooterMidnight = TextView(this).apply {
            setTextColor(Color.parseColor("#EAEFF2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        footerRow.addView(timesFooterImsak)
        footerRow.addView(timesFooterMidnight)
        col.addView(footerRow)

        // Masjid Photo Caption (tap to cycle next photo)
        val photo = MasjidGallery.photos[currentPhotoIndex]
        timesPhotoCaption = TextView(this).apply {
            text = "🖼 ${photo.title} · ${photo.city}  (tap for next)"
            setTextColor(Color.parseColor("#E6FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setShadowLayer(5f, 0f, 1f, Color.BLACK)
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
                isPast -> Color.parseColor("#B0BAC2")
                else -> Color.WHITE
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                if (isNext) {
                    background = pillBg("#BF12352C", 12, "#4069F0AE")
                }
            }
            val nameTv = TextView(this).apply {
                text = e.name
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
                typeface = if (isNext) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeTv = TextView(this).apply {
                text = JafariPrayerCalculator.formatTime(e.epochMillis, tz, prefs.use24Hour)
                setTextColor(color)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
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
    // TAB 1: INTERACTIVE SATELLITE / MAP QIBLA FINDER
    // =========================================================================
    inner class QiblaJsBridge {
        @JavascriptInterface
        fun onCenterMoved(bearingDeg: Double, distKm: Double) {
            runOnUiThread {
                currentQiblaBearing = bearingDeg
                val cardinal = JafariPrayerCalculator.compassCardinal(bearingDeg)
                val distMi = distKm * 0.621371
                qiblaBearingTv?.text = String.format(Locale.US, "Qibla: %.1f° %s", bearingDeg, cardinal)
                qiblaDistTv?.text = String.format(Locale.US, "Distance: %,.0f mi (%,.0f km) · Drag map to aim", distMi, distKm)
            }
        }
    }

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

        val startLat = if (prefs.hasLocation) prefs.latitude else 38.8977
        val startLng = if (prefs.hasLocation) prefs.longitude else -77.0365
        currentQiblaBearing = JafariPrayerCalculator.qiblaBearingDegrees(startLat, startLng)
        val distKm = JafariPrayerCalculator.distanceToKaabaKm(startLat, startLng)
        val distMi = distKm * 0.621371
        val cardinal = JafariPrayerCalculator.compassCardinal(currentQiblaBearing)

        // Header info card
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg("#CC101417", 16, "#33F5C542")
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(4), dp(12), dp(8))
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        qiblaBearingTv = TextView(this).apply {
            text = String.format(Locale.US, "Qibla: %.1f° %s", currentQiblaBearing, cardinal)
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        qiblaLayerBtn = smallChip(if (isSatelliteMode) "🗺 Map" else "🛰 Satellite") {
            isSatelliteMode = !isSatelliteMode
            qiblaLayerBtn?.text = if (isSatelliteMode) "🗺 Map" else "🛰 Satellite"
            qiblaWebView?.evaluateJavascript("setSatelliteMode($isSatelliteMode);", null)
        }
        val myGpsBtn = smallChip("📍 GPS") {
            centerQiblaOnLiveGps()
        }
        titleRow.addView(qiblaBearingTv)
        titleRow.addView(qiblaLayerBtn)
        titleRow.addView(hSpace(6))
        titleRow.addView(myGpsBtn)
        infoCard.addView(titleRow)

        qiblaDistTv = TextView(this).apply {
            text = String.format(Locale.US, "Distance: %,.0f mi (%,.0f km) · Drag map to aim", distMi, distKm)
            setTextColor(Color.parseColor("#CFD8DC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(3), 0, dp(2))
        }
        infoCard.addView(qiblaDistTv)

        qiblaHeadingView = TextView(this).apply {
            text = "Phone heading: calibrating compass..."
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        infoCard.addView(qiblaHeadingView)
        col.addView(infoCard)

        // Interactive Map WebView
        qiblaPageLoaded = false
        pendingQiblaCenter = null
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
            addJavascriptInterface(QiblaJsBridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    qiblaPageLoaded = true
                    pendingQiblaCenter?.let { (lat, lng) ->
                        view?.evaluateJavascript("centerOnGps($lat, $lng);", null)
                        pendingQiblaCenter = null
                    }
                }
            }
        }
        qiblaWebView = web

        val html = buildQiblaHtml(startLat, startLng, isSatelliteMode)
        web.loadDataWithBaseURL("https://jafariprayer.local/", html, "text/html", "UTF-8", null)
        col.addView(web)

        contentFrame.addView(col)
        startCompass()
        // Automatically snap to live GPS when opening Qibla tab
        centerQiblaOnLiveGps()
    }

    private fun centerQiblaOnLiveGps() {
        val hasFine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(this)
        val cts = CancellationTokenSource()
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        moveQiblaMapTo(loc.latitude, loc.longitude)
                    } else {
                        client.lastLocation.addOnSuccessListener { last ->
                            if (last != null) {
                                moveQiblaMapTo(last.latitude, last.longitude)
                            }
                        }
                    }
                }
        } catch (_: SecurityException) {
        }
    }

    private fun moveQiblaMapTo(lat: Double, lng: Double) {
        if (qiblaPageLoaded && qiblaWebView != null) {
            qiblaWebView?.evaluateJavascript("centerOnGps($lat, $lng);", null)
        } else {
            pendingQiblaCenter = Pair(lat, lng)
        }
    }

    private fun buildQiblaHtml(lat: Double, lng: Double, satelliteStart: Boolean): String {
        return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #0d1117; }
</style>
</head>
<body>
<div id="map"></div>
<script>
  const kaabaLat = 21.422487;
  const kaabaLng = 39.826206;

  const map = L.map('map', { zoomControl: true }).setView([$lat, $lng], 17);

  const satLayer = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
    maxNativeZoom: 18,
    maxZoom: 20,
    attribution: 'Esri Satellite'
  });

  const satLabels = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}', {
    maxNativeZoom: 18,
    maxZoom: 20,
    opacity: 0.8
  });

  const satPlaces = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}', {
    maxNativeZoom: 18,
    maxZoom: 20,
    opacity: 0.85
  });

  const satGroup = L.layerGroup([satLayer, satLabels, satPlaces]);

  const streetLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxNativeZoom: 19,
    maxZoom: 20,
    attribution: 'OpenStreetMap'
  });

  let activeIsSat = $satelliteStart;
  if (activeIsSat) { satGroup.addTo(map); } else { streetLayer.addTo(map); }

  function setSatelliteMode(useSat) {
    if (useSat === activeIsSat) return;
    activeIsSat = useSat;
    if (useSat) {
      map.removeLayer(streetLayer);
      satGroup.addTo(map);
    } else {
      map.removeLayer(satGroup);
      streetLayer.addTo(map);
    }
  }

  function toRad(d) { return d * Math.PI / 180; }
  function toDeg(r) { return r * 180 / Math.PI; }

  function geodesicPoints(lat1, lon1, lat2, lon2, steps) {
    const p1 = toRad(lat1), l1 = toRad(lon1);
    const p2 = toRad(lat2), l2 = toRad(lon2);
    const sinHalfLat = Math.sin((p2 - p1) / 2);
    const sinHalfLon = Math.sin((l2 - l1) / 2);
    const d = 2 * Math.asin(Math.min(1, Math.sqrt(
      sinHalfLat * sinHalfLat + Math.cos(p1) * Math.cos(p2) * sinHalfLon * sinHalfLon
    )));
    if (d < 1e-7) return [[lat1, lon1], [lat2, lon2]];
    const pts = [];
    for (let i = 0; i <= steps; i++) {
      const f = i / steps;
      const A = Math.sin((1 - f) * d) / Math.sin(d);
      const B = Math.sin(f * d) / Math.sin(d);
      const x = A * Math.cos(p1) * Math.cos(l1) + B * Math.cos(p2) * Math.cos(l2);
      const y = A * Math.cos(p1) * Math.sin(l1) + B * Math.cos(p2) * Math.sin(l2);
      const z = A * Math.sin(p1) + B * Math.sin(p2);
      pts.push([toDeg(Math.atan2(z, Math.sqrt(x * x + y * y))), toDeg(Math.atan2(y, x))]);
    }
    return pts;
  }

  function calcBearing(lat1, lon1) {
    const p1 = toRad(lat1), p2 = toRad(kaabaLat), dL = toRad(kaabaLng - lon1);
    const y = Math.sin(dL) * Math.cos(p2);
    const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dL);
    return (toDeg(Math.atan2(y, x)) + 360) % 360;
  }

  function calcDistKm(lat1, lon1) {
    const R = 6371;
    const dLat = toRad(kaabaLat - lat1), dLon = toRad(kaabaLng - lon1);
    const a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
              Math.cos(toRad(lat1)) * Math.cos(toRad(kaabaLat)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  const qiblaLine = L.polyline([], { color: '#F5C542', weight: 4, opacity: 0.95 }).addTo(map);

  const centerPin = L.circleMarker([$lat, $lng], {
    radius: 8, fillColor: '#69F0AE', color: '#000000', weight: 2.5, fillOpacity: 1
  }).addTo(map);

  L.circleMarker([kaabaLat, kaabaLng], {
    radius: 8, fillColor: '#F5C542', color: '#000000', weight: 2, fillOpacity: 1
  }).addTo(map).bindPopup('Kaaba, Makkah');

  function updateFromCenter() {
    const c = map.getCenter();
    centerPin.setLatLng(c);
    qiblaLine.setLatLngs(geodesicPoints(c.lat, c.lng, kaabaLat, kaabaLng, 100));
    if (window.AndroidBridge && window.AndroidBridge.onCenterMoved) {
      window.AndroidBridge.onCenterMoved(calcBearing(c.lat, c.lng), calcDistKm(c.lat, c.lng));
    }
  }

  map.on('move', updateFromCenter);
  map.on('zoomend', updateFromCenter);
  updateFromCenter();

  function centerOnGps(lat, lng) {
    map.setView([lat, lng], 17);
    updateFromCenter();
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
            background = pillBg("#B31A2024", 14, "#38FFFFFF")
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

        val savedHeader = TextView(this).apply {
            text = "Saved Favorite Cities"
            setTextColor(Color.parseColor("#F5C542"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(0, dp(18), 0, dp(8))
        }
        col.addView(savedHeader)

        val saved = prefs.getSavedCities()
        if (saved.isEmpty()) {
            col.addView(TextView(this).apply {
                text = "Search a city above and tap ★ Save to pin it here for 1-tap switching."
                setTextColor(Color.parseColor("#CFD8DC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
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
            background = pillBg("#99151A1E", 14, "#33FFFFFF")
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
            setTextColor(Color.parseColor("#B0BEC5"))
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

        col.addView(settingToggleCard(
            title = "Calculation Method",
            subtitle = prefs.method.label,
            buttonText = "Switch"
        ) {
            prefs.method = if (prefs.method == CalcMethod.LEVA) CalcMethod.TEHRAN else CalcMethod.LEVA
            syncAndRedraw()
        })

        col.addView(settingToggleCard(
            title = "Displayed Prayer Rows",
            subtitle = if (prefs.showSevenRows) "7 Rows (Fajr, Sunrise, Dhuhr, Asr, Sunset, Maghrib, Isha)" else "5 Rows (Fajr, Sunrise, Dhuhr, Sunset, Maghrib)",
            buttonText = if (prefs.showSevenRows) "7 Rows" else "5 Rows"
        ) {
            prefs.showSevenRows = !prefs.showSevenRows
            syncAndRedraw()
        })

        col.addView(settingToggleCard(
            title = "Clock Format",
            subtitle = if (prefs.use24Hour) "24-Hour (17:11)" else "12-Hour (5:11 PM)",
            buttonText = if (prefs.use24Hour) "24h" else "12h"
        ) {
            prefs.use24Hour = !prefs.use24Hour
            syncAndRedraw()
        })

        val hijriCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg("#99151A1E", 14, "#33FFFFFF")
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
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        hijriCard.addView(hLabel)
        hijriCard.addView(smallChip("−") {
            prefs.hijriOffsetDays = (prefs.hijriOffsetDays - 1).coerceAtLeast(-2)
            PrayerWidgetProvider.updateAllWidgets(this)
            renderCurrentTab()
        })
        hijriCard.addView(hSpace(8))
        hijriCard.addView(smallChip("+") {
            prefs.hijriOffsetDays = (prefs.hijriOffsetDays + 1).coerceAtMost(2)
            PrayerWidgetProvider.updateAllWidgets(this)
            renderCurrentTab()
        })
        col.addView(hijriCard)

        col.addView(settingToggleCard(
            title = "App Background",
            subtitle = if (prefs.useMasjidBackgrounds) "Rotating Masjid Photos" else "AMOLED Pure Black",
            buttonText = if (prefs.useMasjidBackgrounds) "Photos" else "Black"
        ) {
            prefs.useMasjidBackgrounds = !prefs.useMasjidBackgrounds
            loadBackgroundPhoto()
            renderCurrentTab()
        })

        val opacityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg("#99151A1E", 14, "#33FFFFFF")
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
            setTextColor(Color.parseColor("#B0BEC5"))
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

        val notifCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg("#99151A1E", 14, "#33FFFFFF")
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
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
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
            background = pillBg("#99151A1E", 14, "#33FFFFFF")
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
            setTextColor(Color.parseColor("#B0BEC5"))
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
            background = pillBg("#99222A30", 12, "#40FFFFFF")
            setPadding(dp(11), dp(7), dp(11), dp(7))
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
