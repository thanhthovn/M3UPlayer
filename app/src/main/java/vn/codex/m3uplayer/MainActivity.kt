package vn.codex.m3uplayer

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor
import kotlin.math.sin
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

private class OutlinedNumberView(context: android.content.Context) : View(context) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, 144f, resources.displayMetrics
        )
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        strokeJoin = Paint.Join.ROUND
    }
    var number: String = ""
        set(value) { field = value; contentDescription = "Số kênh $value"; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (number.isEmpty()) return
        val x = width / 2f
        val y = 24f * resources.displayMetrics.density - textPaint.fontMetrics.top
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = 4f * resources.displayMetrics.density
        textPaint.color = Color.rgb(231, 75, 42)
        canvas.drawText(number, x, y, textPaint)
        textPaint.style = Paint.Style.FILL
        textPaint.color = Color.rgb(255, 224, 112)
        canvas.drawText(number, x, y, textPaint)
    }
}
data class PlaylistSource(val name: String, val value: String)
data class Channel(val name: String, val url: String, val logo: String)
data class Place(val name: String, val latitude: Double, val longitude: Double)
data class Province(val name: String, val latitude: Double, val longitude: Double, val communes: List<Place>)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("m3u_player", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    private val playlistIo = Executors.newFixedThreadPool(2)
    private val playlistRequestLock = Any()
    @Volatile private var activePlaylistConnection: HttpURLConnection? = null
    private var activePlaylistTask: Future<*>? = null
    private val logoIo = Executors.newFixedThreadPool(6)
    private val logoLock = Any()
    private val logoCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 16).toInt().coerceIn(2 * 1024, 16 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap) = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val pendingLogoTargets = mutableMapOf<String, MutableList<ImageView>>()
    private var channelRenderGeneration = 0
    private var sources = mutableListOf<PlaylistSource>()
    private var channels = listOf<Channel>()
    private var provinces = listOf<Province>()
    private var weatherLatitude = 21.0285
    private var weatherLongitude = 105.8542
    private var weatherPlaceName = "Hà Nội"
    private var weatherProvinceIndex = 0
    private var weatherCommuneIndex = 0
    private var player: ExoPlayer? = null
    private lateinit var root: FrameLayout
    private lateinit var video: PlayerView
    private lateinit var listPanel: LinearLayout
    private lateinit var channelRows: LinearLayout
    private lateinit var playScreen: FrameLayout
    private lateinit var numericChannelOverlay: OutlinedNumberView
    private lateinit var qualityInfo: TextView
    private lateinit var homePanel: LinearLayout
    private lateinit var channelGrid: GridLayout
    private lateinit var clockInfo: TextView
    private lateinit var timeInfo: TextView
    private lateinit var weatherInfo: TextView
    private lateinit var refreshButton: Button
    private lateinit var startupPrompt: TextView
    private var wideWeatherLayout = false
    private var currentChannel = -1
    private var selectedVideoBitrateKbps: Int? = null
    private var startupModeAtLaunch = 0
    private var startupChannelAtLaunch = 1
    private var startupActionPending = false
    @Volatile private var playlistLoadId = 0
    private var listVisible = false
    private var playbackMimeAttempts = listOf<String?>(null)
    private var playbackMimeAttemptIndex = 0
    private val playbackTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    private val playbackSslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(playbackTrustManager), SecureRandom()) }
    }
    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(playbackSslContext.socketFactory, playbackTrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
    private val uiHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            val untilNextMinute = 60_000L - System.currentTimeMillis() % 60_000L
            uiHandler.postDelayed(this, untilNextMinute)
        }
    }
    private val pendingChannelNumber = StringBuilder()
    private val numericTuneTimeout = Runnable { tunePendingChannelNumber() }
    private val hidePlaybackQuality = Runnable {
        if (::qualityInfo.isInitialized) qualityInfo.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        loadSources()
        startupModeAtLaunch = prefs.getInt("startup_action", 0).coerceIn(0, 2)
        startupChannelAtLaunch = prefs.getInt("startup_channel", 1)
        startupActionPending = sources.isNotEmpty() && startupModeAtLaunch != 0
        loadCachedChannels()
        loadPlaces()
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (player != null) returnToHome() else finish()
            }
        })
        uiHandler.post(clockTick)
        refreshWeather()
        renderChannels()
        if (sources.isEmpty()) startupPrompt.visibility = View.VISIBLE
        if (sources.isNotEmpty() && shouldRefreshPlaylist()) loadPlaylist()
        else applyStartupActionIfReady()
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(33, 43, 29)); isFocusableInTouchMode = true }
        playScreen = FrameLayout(this)
        video = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setControllerAutoShow(false)
            setOnClickListener { toggleList() }
        }
        playScreen.addView(video, FrameLayout.LayoutParams(-1, -1))
        qualityInfo = text("", 16, true).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(190, 34, 45, 30))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(88, 112, 62))
            }
            visibility = View.GONE
            contentDescription = "Độ phân giải và bitrate video"
        }
        playScreen.addView(qualityInfo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(24)
            bottomMargin = dp(24)
        })
        numericChannelOverlay = OutlinedNumberView(this).apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Số kênh đang nhập"
        }
        playScreen.addView(numericChannelOverlay, FrameLayout.LayoutParams(-1, -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(playScreen)

        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            setBackgroundColor(Color.rgb(33, 43, 29))
        }
        root.addView(homePanel, FrameLayout.LayoutParams(-1, -1))
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val wideHeader = resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 900
        wideWeatherLayout = wideHeader
        val sourceButton = button("⚙", 30).apply {
            setOnClickListener { showSettingsDialog() }
            contentDescription = "Cài đặt"
            background = actionButtonDrawable(false)
            setOnFocusChangeListener { view, focused -> view.background = actionButtonDrawable(focused) }
        }
        val actionButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actionButtons.addView(sourceButton, LinearLayout.LayoutParams(dp(64), dp(62)))
        refreshButton = button("⟳", 34).apply {
            setOnClickListener { if (sources.isEmpty()) showSettingsDialog() else loadPlaylist(manual = true) }
            background = actionButtonDrawable(false)
            setOnFocusChangeListener { view, focused -> view.background = actionButtonDrawable(focused) }
            gravity = Gravity.CENTER
            textSize = 9f
            includeFontPadding = false
            maxLines = 3
            setPadding(dp(2), 0, dp(2), 0)
        }
        updateRefreshButtonCount()
        actionButtons.addView(refreshButton, LinearLayout.LayoutParams(dp(64), dp(62)).apply { leftMargin = dp(8) })
        header.addView(actionButtons)
        val spacer = Space(this)
        if (wideHeader) header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        clockInfo = text("", 17, true).apply {
            setTextColor(Color.WHITE); gravity = Gravity.END or Gravity.CENTER_VERTICAL; textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = if (wideHeader) 17f else 12f
        }
        header.addView(clockInfo, if (wideHeader) LinearLayout.LayoutParams(dp(300), -2) else LinearLayout.LayoutParams(0, -2, 1f))
        timeInfo = text("", 36, true).apply {
            setTextColor(Color.rgb(255, 238, 185)); gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(69, 88, 48)); cornerRadius = dp(12).toFloat(); setStroke(dp(2), Color.rgb(236, 91, 50))
            }
            textSize = if (wideHeader) 47.5f else 38f
        }
        header.addView(timeInfo, LinearLayout.LayoutParams(if (wideHeader) dp(152) else dp(120), if (wideHeader) dp(72) else dp(61)).apply { leftMargin = if (wideHeader) dp(12) else dp(6) })
        if (wideHeader) {
            weatherInfo = weatherLabel().apply { textSize = 16f; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(16), dp(10), dp(16), dp(10)) }
            header.addView(weatherInfo, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(88)).apply { leftMargin = dp(12) })
            homePanel.addView(header)
        } else {
            homePanel.addView(header)
            weatherInfo = weatherLabel().apply { textSize = 15f; maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(14), dp(10), dp(14), dp(10)) }
            homePanel.addView(weatherInfo, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, dp(96)).apply { topMargin = dp(8); gravity = Gravity.END })
        }
        val gridScroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        channelGrid = GridLayout(this).apply { useDefaultMargins = false; alignmentMode = GridLayout.ALIGN_MARGINS }
        gridScroll.addView(channelGrid)
        homePanel.addView(gridScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        startupPrompt = text("VUI LÒNG NHẤN OK ĐỂ NHẬP URL!", 26, false).apply {
            setTextColor(Color.rgb(255, 232, 150))
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(dp(32), dp(12), dp(32), dp(12))
            visibility = View.GONE
            isFocusable = false
            isClickable = false
        }
        root.addView(startupPrompt, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        listPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(5), dp(24), dp(5))
            background = GradientDrawable().apply { setColor(Color.rgb(34, 45, 30)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(54, 80, 38)) }
            elevation = dp(8).toFloat()
        }
        val panelWidth = resources.displayMetrics.widthPixels
        val panelLp = FrameLayout.LayoutParams(panelWidth, -1, Gravity.END).apply { setMargins(0, dp(2), dp(12), dp(2)) }
        root.addView(listPanel, panelLp)
        listPanel.visibility = View.GONE
        val scroll = ScrollView(this).apply { isFillViewport = true }
        channelRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(channelRows)
        listPanel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.requestFocus()
        setContentView(root)
    }

    private fun loadSources() {
        sources.clear()
        try {
            val arr = JSONArray(prefs.getString("sources", "[]"))
            val loaded = mutableListOf<PlaylistSource>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optBoolean("local", false)) loaded.add(PlaylistSource(o.getString("name"), o.getString("value")))
            }
            val savedDefault = prefs.getInt("default", 0).coerceIn(0, (loaded.size - 1).coerceAtLeast(0))
            loaded.getOrNull(savedDefault)?.let(sources::add)
            if (loaded.size > 1 || arr.length() != sources.size) saveSources()
        } catch (_: Exception) { sources.clear() }
    }

    private fun loadPlaces() {
        try {
            val data = JSONObject(assets.open("locations.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val provinceArray = data.getJSONArray("tinh")
            val communeArray = data.getJSONArray("xa")
            val communeLists = MutableList(provinceArray.length()) { mutableListOf<Place>() }
            for (i in 0 until communeArray.length()) {
                val item = communeArray.getJSONArray(i)
                val provinceIndex = item.getInt(1)
                if (provinceIndex in communeLists.indices) communeLists[provinceIndex].add(Place(item.getString(0), item.getDouble(2), item.getDouble(3)))
            }
            provinces = (0 until provinceArray.length()).map { i ->
                val p = provinceArray.getJSONArray(i)
                Province(p.getString(0), p.getDouble(1), p.getDouble(2), communeLists[i].sortedBy { it.name })
            }
            if (prefs.contains("weather_lat")) {
                weatherLatitude = prefs.getString("weather_lat", "21.0285")!!.toDoubleOrNull() ?: 21.0285
                weatherLongitude = prefs.getString("weather_lon", "105.8542")!!.toDoubleOrNull() ?: 105.8542
                weatherPlaceName = prefs.getString("weather_name", "Hà Nội") ?: "Hà Nội"
                val savedProvince = prefs.getInt("weather_province_index", -1)
                weatherProvinceIndex = if (savedProvince >= 0) savedProvince else provinces.indexOfFirst { weatherPlaceName.contains(shortPlace(it.name), true) }.coerceAtLeast(0)
                weatherProvinceIndex = weatherProvinceIndex.coerceIn(0, (provinces.size - 1).coerceAtLeast(0))
                weatherCommuneIndex = prefs.getInt("weather_commune_index", 0).coerceAtLeast(0)
                if (provinces.isNotEmpty()) weatherPlaceName = formatWeatherLocation(weatherProvinceIndex, weatherCommuneIndex)
            } else {
                val provinceIndex = provinces.indexOfFirst { it.name.contains("Lâm Đồng", true) }.takeIf { it >= 0 } ?: 0
                val province = provinces.getOrNull(provinceIndex)
                if (province != null) {
                    weatherProvinceIndex = provinceIndex
                    val communePosition = province.communes.indexOfFirst { it.name.contains("Phan Thiết", true) }
                    if (communePosition >= 0) {
                        val place = province.communes[communePosition]
                        weatherLatitude = place.latitude
                        weatherLongitude = place.longitude
                        weatherPlaceName = formatWeatherLocation(provinceIndex, communePosition + 1)
                        weatherCommuneIndex = communePosition + 1
                    } else {
                        weatherLatitude = province.latitude
                        weatherLongitude = province.longitude
                        weatherPlaceName = formatWeatherLocation(provinceIndex, 0)
                        weatherCommuneIndex = 0
                    }
                    prefs.edit().putString("weather_lat", weatherLatitude.toString()).putString("weather_lon", weatherLongitude.toString())
                        .putString("weather_name", weatherPlaceName).putInt("weather_province_index", weatherProvinceIndex)
                        .putInt("weather_commune_index", weatherCommuneIndex).apply()
                }
            }
        } catch (_: Exception) { provinces = emptyList() }
    }

    private fun shortPlace(name: String): String {
        val prefixes = listOf("Thành phố ", "Tỉnh ", "Xã ", "Phường ", "Đặc Khu ", "Đặc khu ")
        val prefix = prefixes.firstOrNull { name.startsWith(it, ignoreCase = true) }
        return if (prefix == null) name else name.substring(prefix.length)
    }

    private fun formatWeatherLocation(provinceIndex: Int, communeIndex: Int): String {
        val province = provinces.getOrNull(provinceIndex) ?: return weatherPlaceName
        val provinceType = if (province.name.startsWith("Thành phố ", true)) "Thành Phố" else "Tỉnh"
        val provinceLabel = "$provinceType ${shortPlace(province.name)}"
        val place = province.communes.getOrNull(communeIndex - 1)
        if (communeIndex <= 0 || place == null) return provinceLabel
        val placeType = when {
            place.name.startsWith("Xã ", true) -> "Xã"
            place.name.startsWith("Phường ", true) -> "Phường"
            place.name.startsWith("Đặc Khu ", true) -> "Đặc khu"
            else -> "Phường"
        }
        return "📍 $placeType ${shortPlace(place.name)}\n$provinceLabel"
    }

    private fun weatherLabel() = text("Đang cập nhật thời tiết…", 19, true).apply {
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        background = focusOutlineDrawable(false, 14, 0)
        isFocusable = true
        isClickable = true
        contentDescription = "Chọn vị trí thời tiết"
        setOnFocusChangeListener { view, focused -> view.background = focusOutlineDrawable(focused, 14, 0) }
        setOnClickListener { showLocationDialog() }
    }

    private fun updateClock() {
        if (!::clockInfo.isInitialized) return
        val locale = Locale("vi", "VN")
        val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val now = Date()
        val time = SimpleDateFormat("HH:mm", locale).apply { timeZone = zone }.format(now)
        val pattern = if (resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 900) "EEEE, dd/MM/yyyy" else "dd/MM/yyyy"
        val solar = SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(now)
        val lunar = lunarDate(now)
        clockInfo.text = "$solar\n$lunar"
        if (::timeInfo.isInitialized) timeInfo.text = time
    }

    private fun showLocationDialog() {
        if (provinces.isEmpty()) { toast("Không đọc được danh sách vị trí."); return }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        form.addView(text("TỈNH / THÀNH PHỐ", 16, true).apply { setTextColor(Color.WHITE) })
        val provinceSpinner = Spinner(this).apply {
            background = locationSpinnerBackground(); setPopupBackgroundDrawable(locationPopupBackground())
            isFocusable = true; isFocusableInTouchMode = true
        }
        val provinceNames = provinces.map { shortPlace(it.name) }
        provinceSpinner.adapter = locationSpinnerAdapter(provinceNames)
        form.addView(provinceSpinner, LinearLayout.LayoutParams(-1, dp(56)))
        form.addView(text("XÃ / PHƯỜNG", 16, true).apply { setTextColor(Color.WHITE); setPadding(0, dp(12), 0, 0) })
        val communeSpinner = Spinner(this).apply {
            background = locationSpinnerBackground(); setPopupBackgroundDrawable(locationPopupBackground())
            isFocusable = true; isFocusableInTouchMode = true
        }
        form.addView(communeSpinner, LinearLayout.LayoutParams(-1, dp(56)))
        var chosenProvince = weatherProvinceIndex.coerceIn(0, provinces.lastIndex)
        var chosenCommune = weatherCommuneIndex
        fun fillCommunes(index: Int) {
            val province = provinces[index]
            val choices = listOf("Trung tâm ${shortPlace(province.name)}") + province.communes.map { shortPlace(it.name) }
            communeSpinner.adapter = locationSpinnerAdapter(choices)
            communeSpinner.setSelection(chosenCommune.coerceIn(0, choices.lastIndex))
        }
        provinceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                chosenProvince = position
                chosenCommune = if (position == weatherProvinceIndex) weatherCommuneIndex else 0
                fillCommunes(position)
            }
        }
        communeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { chosenCommune = position }
        }
        provinceSpinner.setSelection(chosenProvince)
        fillCommunes(chosenProvince)
        val dialog = AlertDialog.Builder(this).setTitle("VỊ TRÍ THỜI TIẾT").setView(form)
            .setNegativeButton("HỦY", null).setPositiveButton("OK", null).create()
        dialog.setOnShowListener {
            applyDialogTheme(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val province = provinces[chosenProvince]
                val place = if (chosenCommune == 0) Place(shortPlace(province.name), province.latitude, province.longitude) else province.communes[chosenCommune - 1]
                weatherLatitude = place.latitude
                weatherLongitude = place.longitude
                weatherPlaceName = formatWeatherLocation(chosenProvince, chosenCommune)
                weatherProvinceIndex = chosenProvince
                weatherCommuneIndex = chosenCommune
                prefs.edit().putString("weather_lat", weatherLatitude.toString()).putString("weather_lon", weatherLongitude.toString())
                    .putString("weather_name", weatherPlaceName).putInt("weather_province_index", chosenProvince).putInt("weather_commune_index", chosenCommune).apply()
                dialog.dismiss()
                refreshWeather()
            }
        }
        dialog.show()
    }

    private fun locationSpinnerAdapter(items: List<String>) = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
        init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(Color.WHITE); textSize = 18f; setPadding(dp(12), dp(8), dp(42), dp(8)); isFocusable = false
            }
        override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                setTextColor(Color.rgb(255, 245, 214)); textSize = 18f; maxLines = 2; setSingleLine(false); ellipsize = null
                gravity = Gravity.CENTER_VERTICAL; minHeight = dp(64)
                layoutParams = android.widget.AbsListView.LayoutParams(-1, -2)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                isFocusable = false; isClickable = false; background = selectionRowBackground()
            }
    }

    private fun locationPopupBackground() = GradientDrawable().apply {
        setColor(Color.rgb(29, 39, 27)); cornerRadius = dp(10).toFloat()
        setStroke(dp(1), Color.rgb(66, 88, 51))
    }

    private fun themedChoiceAdapter(
        items: Array<String>,
        singleChoice: Boolean = false,
        textColor: Int = Color.rgb(255, 232, 150),
        focusColor: Int = Color.rgb(238, 145, 54)
    ) = object : ArrayAdapter<String>(
        this,
        if (singleChoice) android.R.layout.simple_list_item_single_choice else android.R.layout.simple_list_item_1,
        items
    ) {
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                setTextColor(textColor); textSize = 18f
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = selectionRowBackground(focusColor)
                if (this is CheckedTextView && singleChoice) {
                    checkMarkTintList = android.content.res.ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(Color.rgb(255, 214, 79), Color.rgb(72, 82, 59))
                    )
                }
            }
    }

    private fun selectionRowBackground(focusColor: Int = Color.rgb(238, 145, 54)) = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var highlighted = false
        override fun draw(canvas: Canvas) {
            val rect = RectF(bounds).apply { inset(dp(2).toFloat(), dp(2).toFloat()) }
            paint.style = Paint.Style.FILL
            paint.color = if (highlighted) Color.rgb(70, 88, 48) else Color.rgb(34, 45, 30)
            canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(if (highlighted) 2 else 1).toFloat()
            paint.color = if (highlighted) focusColor else Color.rgb(58, 76, 43)
            canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)
        }
        override fun onStateChange(state: IntArray): Boolean {
            val next = state.contains(android.R.attr.state_focused) || state.contains(android.R.attr.state_pressed) ||
                state.contains(android.R.attr.state_selected) || state.contains(android.R.attr.state_activated)
            if (next == highlighted) return false
            highlighted = next; invalidateSelf(); return true
        }
        override fun isStateful() = true
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun locationSpinnerBackground() = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var focused = false
        override fun draw(canvas: Canvas) {
            val rect = RectF(bounds)
            paint.style = Paint.Style.FILL
            paint.color = if (focused) Color.rgb(55, 72, 42) else Color.rgb(40, 53, 36)
            canvas.drawRoundRect(rect, dp(8).toFloat(), dp(8).toFloat(), paint)
            if (focused) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3).toFloat(); paint.color = Color.rgb(238, 145, 54)
                val strokeRect = RectF(rect).apply { inset(dp(2).toFloat(), dp(2).toFloat()) }
                canvas.drawRoundRect(strokeRect, dp(7).toFloat(), dp(7).toFloat(), paint)
            }
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(255, 214, 79)
            val cx = bounds.right - dp(18).toFloat(); val cy = bounds.centerY().toFloat()
            val arrow = Path().apply {
                moveTo(cx - dp(6).toFloat(), cy - dp(3).toFloat())
                lineTo(cx + dp(6).toFloat(), cy - dp(3).toFloat())
                lineTo(cx, cy + dp(4).toFloat())
                close()
            }
            canvas.drawPath(arrow, paint)
        }
        override fun onStateChange(state: IntArray): Boolean {
            val next = state.contains(android.R.attr.state_focused) || state.contains(android.R.attr.state_pressed)
            if (next == focused) return false
            focused = next; invalidateSelf(); return true
        }
        override fun isStateful() = true
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun refreshWeather() {
        if (!::weatherInfo.isInitialized) return
        setWeatherText("$weatherPlaceName\n⌁  Đang tải")
        val lat = weatherLatitude
        val lon = weatherLongitude
        io.execute {
            try {
                val endpoint = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=Asia%2FBangkok"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply { connectTimeout = 10000; readTimeout = 12000 }
                val response = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }).getJSONObject("current")
                connection.disconnect()
                val temperature = response.getDouble("temperature_2m").toInt()
                val code = response.getInt("weather_code")
                val condition = weatherCondition(code)
                runOnUiThread { if (lat == weatherLatitude && lon == weatherLongitude) setWeatherText("$weatherPlaceName\n$condition  $temperature°C") }
            } catch (_: Exception) {
                runOnUiThread { if (lat == weatherLatitude && lon == weatherLongitude) setWeatherText("$weatherPlaceName\n☁  Chưa có dữ liệu") }
            }
        }
        uiHandler.removeCallbacks(weatherTick)
        uiHandler.postDelayed(weatherTick, 30 * 60 * 1000L)
    }

    private val weatherTick = Runnable { refreshWeather() }

    private fun setWeatherText(value: String) {
        weatherInfo.text = value
        val measured = (value.lines().maxOfOrNull { weatherInfo.paint.measureText(it) } ?: dp(160).toFloat()).toInt()
        val naturalWidth = measured + weatherInfo.paddingLeft + weatherInfo.paddingRight + dp(4)
        val screenWidth = resources.displayMetrics.widthPixels
        val maxWidth = if (wideWeatherLayout) {
            (screenWidth - dp(538)).coerceAtLeast(dp(180)).coerceAtMost(dp(420))
        } else (screenWidth - dp(48)).coerceAtLeast(dp(180))
        weatherInfo.maxWidth = maxWidth
        val params = weatherInfo.layoutParams as LinearLayout.LayoutParams
        params.width = naturalWidth.coerceIn(dp(180), maxWidth)
        weatherInfo.layoutParams = params
    }

    private fun weatherCondition(code: Int): String = when (code) {
        0 -> "☀ Trời quang"
        1 -> "🌤 Ít mây"
        2 -> "⛅ Có mây"
        3 -> "☁ Nhiều mây"
        45, 48 -> "🌫 Sương mù"
        51, 53, 55, 56, 57 -> "🌦 Mưa phùn"
        61, 63, 65, 66, 67 -> "🌧 Có mưa"
        71, 73, 75, 77, 85, 86 -> "❄ Có tuyết"
        80, 81, 82 -> "🌧 Mưa rào"
        95, 96, 99 -> "⛈ Dông"
        else -> "🌡 Thời tiết"
    }

    private fun lunarDate(date: Date): String {
        val zone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        val cal = Calendar.getInstance(zone).apply { time = date }
        val solarDay = cal.get(Calendar.DAY_OF_MONTH); val solarMonth = cal.get(Calendar.MONTH) + 1; val solarYear = cal.get(Calendar.YEAR)
        val lunar = solarToLunar(solarDay, solarMonth, solarYear, 7.0)
        val can = arrayOf("Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý")[((lunar[2] + 6) % 10)]
        val chi = arrayOf("Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi")[(lunar[2] + 8) % 12]
        val lunarMonths = arrayOf("Giêng", "Hai", "Ba", "Tư", "Năm", "Sáu", "Bảy", "Tám", "Chín", "Mười", "Mười Một", "Chạp")
        val monthName = lunarMonths[(lunar[1] - 1).coerceIn(0, 11)]
        return "Mồng ${lunar[0]} tháng $monthName${if (lunar[3] == 1) " nhuận" else ""}\nNăm $can $chi"
    }

    private fun solarToLunar(day: Int, month: Int, year: Int, timeZone: Double): IntArray {
        val dayNumber = jdFromDate(day, month, year)
        val k = floor((dayNumber - 2415021.076998695) / 29.530588853).toInt()
        var monthStart = newMoonDay(k + 1, timeZone)
        if (monthStart > dayNumber) monthStart = newMoonDay(k, timeZone)
        var a11 = lunarMonth11(year, timeZone)
        val b11: Int
        var lunarYear: Int
        if (a11 >= monthStart) { lunarYear = year; a11 = lunarMonth11(year - 1, timeZone); b11 = lunarMonth11(year, timeZone) }
        else { lunarYear = year + 1; b11 = lunarMonth11(year + 1, timeZone) }
        val lunarDay = dayNumber - monthStart + 1
        val diff = floor(((monthStart - a11) / 29.0)).toInt()
        var lunarMonth = diff + 11
        var leap = 0
        if (b11 - a11 > 365) {
            val leapOff = leapMonthOffset(a11, timeZone)
            if (diff >= leapOff) { lunarMonth = diff + 10; if (diff == leapOff) leap = 1 }
        }
        if (lunarMonth > 12) lunarMonth -= 12
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1
        return intArrayOf(lunarDay, lunarMonth, lunarYear, leap)
    }

    private fun jdFromDate(day: Int, month: Int, year: Int): Int {
        val a = floor((14 - month) / 12.0).toInt(); val y = year + 4800 - a; val m = month + 12 * a - 3
        var jd = day + floor((153 * m + 2) / 5.0).toInt() + 365 * y + floor(y / 4.0).toInt() - floor(y / 100.0).toInt() + floor(y / 400.0).toInt() - 32045
        if (jd < 2299161) jd = day + floor((153 * m + 2) / 5.0).toInt() + 365 * y + floor(y / 4.0).toInt() - 32083
        return jd
    }

    private fun newMoonDay(k: Int, timeZone: Double) = floor(newMoon(k) + 0.5 + timeZone / 24).toInt()

    private fun newMoon(k: Int): Double {
        val t = k / 1236.85; val t2 = t * t; val t3 = t2 * t
        val dr = Math.PI / 180
        var jd = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        jd += 0.00033 * sin((166.56 + 132.87 * t - 0.009173 * t2) * dr)
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mPrime = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3
        var c = (0.1734 - 0.000393 * t) * sin(m * dr) + 0.0021 * sin(2 * dr * m)
        c -= 0.4068 * sin(mPrime * dr) + 0.0161 * sin(2 * dr * mPrime) + 0.0004 * sin(3 * dr * mPrime)
        c += 0.0104 * sin(2 * dr * f) - 0.0051 * sin(dr * (m + mPrime)) - 0.0074 * sin(dr * (m - mPrime))
        c += 0.0004 * sin(dr * (2 * f + m)) - 0.0004 * sin(dr * (2 * f - m)) - 0.0006 * sin(dr * (2 * f + mPrime)) + 0.0010 * sin(dr * (2 * f - mPrime)) + 0.0005 * sin(dr * (2 * mPrime + m))
        val deltaT = if (t < -11) 0.001 + 0.000839 * t + 0.0002261 * t2 - 0.00000845 * t3 - 0.000000081 * t * t3 else -0.000278 + 0.000265 * t + 0.000262 * t2
        return jd + c - deltaT
    }

    private fun sunLongitude(jdn: Double): Double {
        val t = (jdn - 2451545.0) / 36525; val t2 = t * t; val dr = Math.PI / 180
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        val dl = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(dr * m) + (0.019993 - 0.000101 * t) * sin(2 * dr * m) + 0.000290 * sin(3 * dr * m)
        return ((l0 + dl) * dr) % (2 * Math.PI)
    }

    private fun sunSector(day: Int, timeZone: Double) = floor(sunLongitude(day - 0.5 - timeZone / 24) / Math.PI * 6).toInt()

    private fun lunarMonth11(year: Int, timeZone: Double): Int {
        val off = jdFromDate(31, 12, year) - 2415021
        val k = floor(off / 29.530588853).toInt()
        var nm = newMoonDay(k, timeZone)
        if (sunSector(nm, timeZone) >= 9) nm = newMoonDay(k - 1, timeZone)
        return nm
    }

    private fun leapMonthOffset(a11: Int, timeZone: Double): Int {
        val k = floor(0.5 + (a11 - 2415021.076998695) / 29.530588853).toInt()
        var last = 0; var i = 1; var arc = sunSector(newMoonDay(k + i, timeZone), timeZone)
        do { last = arc; i++; arc = sunSector(newMoonDay(k + i, timeZone), timeZone) } while (arc != last && i < 14)
        return i - 1
    }

    private fun saveSources() {
        val arr = JSONArray()
        sources.firstOrNull()?.let { arr.put(JSONObject().put("name", it.name).put("value", it.value)) }
        prefs.edit().putString("sources", arr.toString()).putInt("default", 0).apply()
    }

    private fun showSettingsDialog() {
        val items = arrayOf("NHẬP URL", "CÀI ĐẶT TỰ PHÁT", "TỰ ĐỘNG CẬP NHẬT", "THÔNG TIN PHẦN MỀM")
        AlertDialog.Builder(this).setTitle("CÀI ĐẶT").setAdapter(themedChoiceAdapter(items)) { _, which ->
            when (which) {
                0 -> showUrlDialog()
                1 -> showStartupBehaviorDialog()
                2 -> showRefreshIntervalDialog()
                3 -> showSoftwareLicenseDialog()
            }
        }.setNegativeButton("ĐÓNG", null).create().also { dialog ->
            dialog.show(); applyDialogTheme(dialog)
            resizeDialog(dialog, 0.6f)
        }
    }

    private fun showStartupBehaviorDialog() {
        val options = arrayOf("Tự phát kênh số…", "Tự focus đến kênh số…", "Không làm gì cả")
        val checked = when (prefs.getInt("startup_action", 0)) { 1 -> 0; 2 -> 1; else -> 2 }
        AlertDialog.Builder(this).setTitle("CÀI ĐẶT TỰ PHÁT")
            .setSingleChoiceItems(
                themedChoiceAdapter(options, singleChoice = true, textColor = Color.WHITE, focusColor = Color.rgb(255, 214, 79)),
                checked
            ) { dialog, which ->
                when (which) {
                    0 -> { dialog.dismiss(); showStartupChannelDialog(1) }
                    1 -> { dialog.dismiss(); showStartupChannelDialog(2) }
                    else -> {
                        prefs.edit().putInt("startup_action", 0).apply()
                        dialog.dismiss()
                        toast("Đã chọn: Không làm gì cả.")
                    }
                }
            }
            .setNegativeButton("ĐÓNG", null).create().also { dialog ->
                dialog.show(); applyDialogTheme(dialog, listTextColor = Color.WHITE); resizeDialog(dialog, 0.6f)
            }
    }

    private fun showStartupChannelDialog(action: Int) {
        val input = EditText(this).apply {
            textSize = 22f
            setSingleLine(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(128, 153, 91))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("startup_channel", 1).toString())
            setSelection(text.length)
        }
        val title = if (action == 1) "TỰ PHÁT KÊNH SỐ" else "TỰ FOCUS ĐẾN KÊNH SỐ"
        val inputContainer = FrameLayout(this).apply {
            addView(input, FrameLayout.LayoutParams(dp(80), -2, Gravity.CENTER))
        }
        AlertDialog.Builder(this).setTitle(title).setView(inputContainer)
            .setNegativeButton("HỦY", null).setPositiveButton("LƯU", null).create().also { dialog ->
                dialog.setOnShowListener {
                    applyDialogTheme(dialog)
                    centerStartupNumberDialog(dialog)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val number = input.text.toString().toIntOrNull()
                        if (number == null || number < 1) {
                            input.error = "Nhập số kênh từ 1 trở lên"
                            return@setOnClickListener
                        }
                        if (channels.isNotEmpty() && number > channels.size) {
                            input.error = "Playlist hiện có ${channels.size} kênh"
                            return@setOnClickListener
                        }
                        prefs.edit().putInt("startup_action", action).putInt("startup_channel", number).apply()
                        dialog.dismiss()
                        toast("Đã lưu cài đặt mở app.")
                    }
                }
                dialog.show()
                resizeDialog(dialog, 0.44f)
            }
    }

    private fun centerStartupNumberDialog(dialog: AlertDialog) {
        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        dialog.findViewById<TextView>(titleId)?.apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        val panelId = resources.getIdentifier("buttonPanel", "id", "android")
        (dialog.findViewById<View>(panelId) as? LinearLayout)?.apply {
            gravity = Gravity.CENTER
            listOf("leftSpacer", "rightSpacer", "spacer").forEach { name ->
                val spacerId = resources.getIdentifier(name, "id", "android")
                findViewById<View>(spacerId)?.visibility = View.GONE
            }
            listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE).forEach { which ->
                (dialog.getButton(which).layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                    params.weight = 0f
                    dialog.getButton(which).layoutParams = params
                }
            }
        }
    }

    private fun resizeDialog(dialog: AlertDialog, widthFactor: Float) {
        val window = dialog.window ?: return
        window.decorView.post {
            if (!dialog.isShowing) return@post
            val screenWidth = resources.displayMetrics.widthPixels
            val currentWidth = window.decorView.width.takeIf { it > 0 } ?: screenWidth
            val maximumWidth = (screenWidth - dp(24)).coerceAtLeast(dp(1))
            val minimumWidth = dp(if (widthFactor <= 0.4f) 120 else 180).coerceAtMost(screenWidth)
            val width = (currentWidth * widthFactor).toInt().coerceIn(minimumWidth, maximumWidth)
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun applyStartupActionIfReady() {
        if (!startupActionPending || channels.isEmpty()) return
        startupActionPending = false
        val index = startupChannelAtLaunch - 1
        if (index !in channels.indices) {
            toast("Playlist hiện có ${channels.size} kênh, không có kênh số $startupChannelAtLaunch.")
            return
        }
        when (startupModeAtLaunch) {
            1 -> playChannel(index)
            2 -> focusHomeChannel(index)
        }
    }

    private fun focusHomeChannel(index: Int, attempts: Int = 0) {
        if (player != null || isFinishing) return
        val target = channelGrid.getChildAt(index)
        if (target != null) {
            target.post {
                val scrollView = channelGrid.parent as? ScrollView
                scrollView?.scrollTo(0, target.top)
                target.requestFocus()
                if (!target.isFocused && attempts < 50) {
                    uiHandler.postDelayed({ focusHomeChannel(index, attempts + 1) }, 40L)
                }
            }
        }
        else if (attempts < 50) uiHandler.postDelayed({ focusHomeChannel(index, attempts + 1) }, 20L)
    }

    private fun applyDialogTheme(dialog: AlertDialog, listTextColor: Int = Color.rgb(255, 232, 150)) {
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.rgb(34, 45, 30)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(66, 88, 51))
        })
        dialog.window?.decorView?.let { tintDialogText(it) }
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply {
                setTextColor(Color.rgb(255, 238, 185))
                background = dialogButtonDrawable()
                minHeight = dp(48)
                setPadding(dp(14), 0, dp(14), 0)
                stateListAnimator = null
            }
        }
        dialog.listView?.let { list ->
            list.setBackgroundColor(Color.rgb(34, 45, 30))
            for (i in 0 until list.childCount) {
                list.getChildAt(i)?.findViewById<TextView>(android.R.id.text1)?.setTextColor(listTextColor)
            }
        }
    }

    private fun tintDialogText(view: View) {
        if (view is TextView) view.setTextColor(Color.WHITE)
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) tintDialogText(view.getChildAt(index))
        }
    }

    private fun dialogButtonDrawable() = StateListDrawable().apply {
        fun shape(focused: Boolean) = GradientDrawable().apply {
            setColor(if (focused) Color.rgb(53, 68, 40) else Color.TRANSPARENT)
            cornerRadius = dp(8).toFloat()
            if (focused) setStroke(dp(2), Color.rgb(255, 214, 79))
        }
        addState(intArrayOf(android.R.attr.state_focused), shape(true))
        addState(intArrayOf(android.R.attr.state_pressed), shape(true))
        addState(intArrayOf(), shape(false))
    }

    private fun showSoftwareLicenseDialog() {
        val licenseText = try {
            val rawLicense = assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
                .replace("\r\n", "\n").replace('\r', '\n').trim()
            rawLicense.split(Regex("\\n\\s*\\n"))
                .joinToString("\n\n") { paragraph ->
                    paragraph.lineSequence().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ")
                }
        }
        catch (_: Exception) { "Không đọc được tệp GPL-3.0 đi kèm ứng dụng." }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (_: Exception) { "1.0" }
        val appInfo = "THÔNG TIN ỨNG DỤNG\n" +
            "TV m3u8 Player\n" +
            "Xem TV ở nhà bằng URL .m3u8\n" +
            "Cập nhật hàng ngày\n" +
            "Version $version\n" +
            "(c) 2026 Thánh Thơ\n" +
            "0984.394.494"
        val licenseContent = "TV m3u8 có sử dụng và chỉnh sửa phần mã phát từ M3UAndroid của oxyroid.\n" +
            "Thay đổi cho TV m3u8 ngày 31/10/2026.\n" +
            "Ứng dụng được phân phối theo GNU GPL phiên bản 3.\n" +
            "Phần mềm được cung cấp không bảo hành. Bản giấy phép đầy đủ nằm bên dưới.\n\n" + licenseText
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(16))
            addView(text(appInfo, 17, true).apply {
                setTextColor(Color.rgb(255, 232, 150)); gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(dp(8), dp(4), dp(8), dp(16)); setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2))
            addView(View(this@MainActivity).apply { setBackgroundColor(Color.rgb(66, 88, 51)) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(text(licenseContent, 14, false).apply {
                setTextColor(Color.WHITE)
                gravity = Gravity.START
                textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                setPadding(dp(8), dp(14), dp(8), dp(4))
                setLineSpacing(dp(3).toFloat(), 1f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        AlertDialog.Builder(this).setTitle("THÔNG TIN PHẦN MỀM").setView(scroll)
            .setPositiveButton("ĐÓNG", null).create().also { dialog ->
                dialog.show(); applyDialogTheme(dialog); resizeDialog(dialog, 1.3f)
            }
    }

    private val refreshIntervals = intArrayOf(1, 3, 7, 30)

    private fun showRefreshIntervalDialog() {
        val current = prefs.getInt("refresh_interval_days", 1).let { value -> refreshIntervals.indexOf(value).coerceAtLeast(0) }
        val choices = arrayOf("Mỗi 1 ngày", "Mỗi 3 ngày", "Mỗi 7 ngày", "Mỗi 30 ngày")
        val dialog = AlertDialog.Builder(this)
            .setTitle("TỰ ĐỘNG CẬP NHẬT PLAYLIST")
            .setAdapter(themedChoiceAdapter(choices, singleChoice = true)) { dialog, which ->
                prefs.edit().putInt("refresh_interval_days", refreshIntervals[which]).apply()
                dialog.dismiss()
                if (sources.isNotEmpty() && shouldRefreshPlaylist()) loadPlaylist()
            }
            .setNegativeButton("ĐÓNG", null)
            .create()
        dialog.show()
        dialog.listView.choiceMode = ListView.CHOICE_MODE_SINGLE
        dialog.listView.setItemChecked(current, true)
        applyDialogTheme(dialog)
        resizeDialog(dialog, 0.6f)
    }

    private fun shouldRefreshPlaylist(): Boolean {
        if (sources.isEmpty()) return false
        val selectedSource = sources.first().value
        if (prefs.getString("playlist_cache_source", null) != selectedSource) return true
        val intervalDays = prefs.getInt("refresh_interval_days", 1).coerceIn(1, 30)
        val lastUpdate = prefs.getLong("playlist_last_update", 0L)
        return lastUpdate == 0L || System.currentTimeMillis() - lastUpdate >= intervalDays * 24L * 60L * 60L * 1000L
    }

    private fun loadCachedChannels() {
        if (sources.isEmpty()) { channels = emptyList(); return }
        val selectedSource = sources.first().value
        if (prefs.getString("playlist_cache_source", null) != selectedSource) { channels = emptyList(); return }
        channels = try {
            val array = JSONArray(prefs.getString("playlist_cache", "[]"))
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Channel(item.getString("name"), item.getString("url"), item.optString("logo", ""))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun serializeChannels(parsed: List<Channel>): String {
        val array = JSONArray()
        parsed.forEach { channel -> array.put(JSONObject().put("name", channel.name).put("url", channel.url).put("logo", channel.logo)) }
        return array.toString()
    }

    private fun saveCachedChannels(json: String, sourceUrl: String) {
        prefs.edit().putString("playlist_cache", json).putString("playlist_cache_source", sourceUrl)
            .putLong("playlist_last_update", System.currentTimeMillis()).apply()
    }

    private fun showUrlDialog() {
        val savedUrl = sources.firstOrNull()?.value.orEmpty()
        val input = EditText(this).apply {
            textSize = 18f; setSingleLine(true); setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(185, 199, 166))
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(128, 153, 91))
            setText("https://")
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this).setTitle(if (savedUrl.isBlank()) "NHẬP URL PLAYLIST" else "ĐỔI URL PLAYLIST").setView(input)
            .setNegativeButton("HỦY", null).setPositiveButton("LƯU & TẢI", null).create().also { d ->
                d.setOnShowListener { applyDialogTheme(d); d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().trim()
                    if (value.isBlank() || value == "https://" || value == "http://") {
                        input.error = "Vui lòng nhập đầy đủ URL!"
                        return@setOnClickListener
                    }
                    if (!(value.startsWith("http://") || value.startsWith("https://"))) { input.error = "Nhập link http hoặc https"; return@setOnClickListener }
                    val name = value.substringAfterLast('/').substringBefore('?').ifBlank { "Playlist" }
                    d.dismiss()
                    loadPlaylist(sourceOverride = PlaylistSource(name, value))
                } }
                d.show()
            }
    }

    private fun loadPlaylist(manual: Boolean = false, sourceOverride: PlaylistSource? = null) {
        val loadId = ++playlistLoadId
        synchronized(playlistRequestLock) {
            activePlaylistConnection?.disconnect()
            activePlaylistConnection = null
            activePlaylistTask?.cancel(true)
            activePlaylistTask = null
        }
        if (player == null) {
            listVisible = false
            homePanel.visibility = View.VISIBLE
            listPanel.visibility = View.GONE
        } else showList(true)
        if (sources.isEmpty() && sourceOverride == null) {
            channels = emptyList()
            renderChannels()
            return
        }
        refreshButton.isEnabled = false
        refreshButton.alpha = .55f
        val source = sourceOverride ?: sources.first()
        val requestTask = playlistIo.submit {
            try {
                if (isDirectMediaUrl(source.value) && !source.value.contains(".m3u8", true)) {
                    val directName = source.name.substringBeforeLast('.', source.name).ifBlank { "Kênh trực tiếp" }
                    val directChannels = listOf(Channel(directName, source.value, ""))
                    val cachedJson = serializeChannels(directChannels)
                    runOnUiThread { if (loadId == playlistLoadId) {
                        refreshButton.isEnabled = true; refreshButton.alpha = 1f
                        if (sourceOverride != null) { sources.clear(); sources.add(source); saveSources() }
                        startupPrompt.visibility = View.GONE
                        channels = directChannels; saveCachedChannels(cachedJson, source.value); renderChannels()
                        applyStartupActionIfReady()
                        if (manual) toast("Đã cập nhật playlist.")
                    } }
                    return@submit
                }
                var resolvedPlaylistUrl = source.value
                val connection = (URL(source.value).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "TV-m3u8/1.0")
                    setRequestProperty("Accept", "application/vnd.apple.mpegurl, audio/x-mpegurl, text/plain, */*")
                    val host = url.host.orEmpty()
                    if (host.equals("freem3u.xyz", true) || host.endsWith(".freem3u.xyz", true)) {
                        setRequestProperty("Referer", "https://freem3u.xyz/")
                        setRequestProperty("Origin", "https://freem3u.xyz")
                    }
                }
                val registered = synchronized(playlistRequestLock) {
                    if (loadId != playlistLoadId) false else { activePlaylistConnection = connection; true }
                }
                if (!registered) { connection.disconnect(); return@submit }
                val raw = try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("Máy chủ trả HTTP $code")
                    resolvedPlaylistUrl = connection.url.toString()
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    connection.disconnect()
                    synchronized(playlistRequestLock) {
                        if (activePlaylistConnection === connection) activePlaylistConnection = null
                    }
                }
                val content = raw.removePrefix("\uFEFF").trimStart()
                if (content.startsWith("<html", true) || content.startsWith("<!doctype", true)) throw IOException("URL trả về trang web, không phải playlist M3U.")
                val isSingleHlsStream = content.lineSequence().any { line ->
                    val tag = line.trim().uppercase(Locale.ROOT)
                    tag.startsWith("#EXT-X-TARGETDURATION") || tag.startsWith("#EXT-X-STREAM-INF") ||
                        tag.startsWith("#EXT-X-MEDIA-SEQUENCE") || tag.startsWith("#EXT-X-MAP:") || tag.startsWith("#EXT-X-PART:")
                } && !content.contains("tvg-logo=", true) && !content.contains("group-title=", true)
                val parsed = if (isSingleHlsStream) listOf(Channel("Kênh trực tiếp", resolvedPlaylistUrl, ""))
                    else parseM3u(raw.removePrefix("\uFEFF"), resolvedPlaylistUrl)
                if (parsed.isEmpty()) throw IOException("Không tìm thấy kênh trong URL này.")
                val cachedJson = serializeChannels(parsed)
                runOnUiThread { if (loadId == playlistLoadId) {
                    refreshButton.isEnabled = true; refreshButton.alpha = 1f
                    if (sourceOverride != null) { sources.clear(); sources.add(source); saveSources() }
                    startupPrompt.visibility = View.GONE
                    channels = parsed; saveCachedChannels(cachedJson, source.value); renderChannels()
                    applyStartupActionIfReady()
                    if (manual) toast("Đã cập nhật ${parsed.size} kênh.")
                } }
            } catch (e: Exception) {
                runOnUiThread {
                    if (loadId == playlistLoadId) {
                        refreshButton.isEnabled = true; refreshButton.alpha = 1f
                        val detail = e.message?.take(100) ?: "kiểm tra kết nối và URL"
                        val message = if (sourceOverride != null) "Không tải được list <${source.name}>: $detail"
                            else if (channels.isEmpty()) "Không tải được playlist: $detail"
                            else "Cập nhật lỗi · vẫn giữ ${channels.size} kênh cũ: $detail"
                        toast(message)
                        applyStartupActionIfReady()
                    }
                }
            }
        }
        synchronized(playlistRequestLock) {
            if (loadId == playlistLoadId) activePlaylistTask = requestTask else requestTask.cancel(true)
        }
    }

    private fun parseM3u(raw: String, base: String): List<Channel> {
        val out = mutableListOf<Channel>(); var name = "Kênh"; var logo = ""
        raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfterLast(',', "Kênh").trim().ifBlank { "Kênh" }
                logo = Regex("(?:tvg-logo|logo)=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1).orEmpty()
            } else if (!line.startsWith("#")) {
                val target = try {
                    if (listOf("http://", "https://", "content://", "file://", "rtsp://", "rtmp://", "udp://", "webrtc://").any { line.startsWith(it, true) }) line
                    else URL(URL(base), line).toString()
                } catch (_: Exception) { line }
                out.add(Channel(name, target, logo)); name = "Kênh"; logo = ""
            }
        }
        return out
    }

    private fun renderChannels() {
        val generation = ++channelRenderGeneration
        updateRefreshButtonCount()
        channelRows.removeAllViews()
        channelGrid.removeAllViews()
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val columns = when { widthDp >= 1500 -> 6; widthDp >= 800 -> 5; widthDp >= 500 -> 3; else -> 2 }
        channelGrid.columnCount = columns
        if (channels.isNotEmpty()) renderChannelBatch(generation, 0)
    }

    private fun updateRefreshButtonCount() {
        if (!::refreshButton.isInitialized) return
        val countText = "${channels.size}\nKÊNH\n"
        val label = android.text.SpannableString(countText + "⟳")
        label.setSpan(android.text.style.RelativeSizeSpan(1.5f), 0, countText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 238, 185)), 0, countText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val channelLabelStart = countText.indexOf("KÊNH")
        label.setSpan(object : android.text.style.LineHeightSpan {
            override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
                val tighten = dp(5)
                fm.descent -= tighten
                fm.bottom -= tighten
            }
        }, channelLabelStart, channelLabelStart + "KÊNH".length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(android.text.style.AbsoluteSizeSpan(26, true), countText.length, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(android.text.style.StyleSpan(Typeface.BOLD), countText.length, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        refreshButton.text = label
        refreshButton.contentDescription = "Cập nhật playlist, ${channels.size} kênh"
    }

    private fun renderChannelBatch(generation: Int, start: Int) {
        if (generation != channelRenderGeneration || isFinishing) return
        val end = (start + 24).coerceAtMost(channels.size)
        for (index in start until end) {
            val channel = channels[index]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(8))
                background = channelItemDrawable(false, 16)
                isFocusable = true
                isClickable = true
                contentDescription = "Phát kênh ${channel.name}"
                setOnClickListener { playChannel(index) }
                setOnFocusChangeListener { view, focused ->
                    view.background = channelItemDrawable(focused, 16)
                }
            }
            val gridLogo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                contentDescription = "Logo ${channel.name}"
                setImageResource(android.R.drawable.ic_media_play)
            }
            if (channel.logo.isNotBlank()) loadLogo(channel.logo, gridLogo)
            card.addView(gridLogo, LinearLayout.LayoutParams(-1, dp(82)))
            card.addView(text(channel.name, 19, true).apply {
                setTextColor(Color.WHITE); maxLines = 2; gravity = Gravity.CENTER; setPadding(dp(3), dp(7), dp(3), 0)
            }, LinearLayout.LayoutParams(-1, dp(58)))
            val gridLp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = dp(166); setMargins(dp(7), dp(7), dp(7), dp(7))
            }
            channelGrid.addView(card, gridLp)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(5), dp(8), dp(5))
                background = channelItemDrawable(false, 12)
                isFocusable = true; isClickable = true
                setOnClickListener { playChannel(index) }
                setOnFocusChangeListener { view, focused ->
                    view.background = channelItemDrawable(focused, 12)
                }
            }
            val logo = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(5), dp(3), dp(5), dp(3))
                contentDescription = "Logo ${channel.name}"
                setImageResource(android.R.drawable.ic_media_play)
            }
            if (channel.logo.isNotBlank()) loadLogo(channel.logo, logo)
            row.addView(logo, LinearLayout.LayoutParams(dp(66), dp(54)))
            val label = text(channel.name, 20, true).apply { setTextColor(Color.WHITE); maxLines = 2; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(6), 0) }
            row.addView(label, LinearLayout.LayoutParams(0, dp(64), 1f))
            row.addView(text("▶", 23, true).apply { setTextColor(Color.rgb(205, 214, 147)) }, LinearLayout.LayoutParams(dp(42), -2))
            channelRows.addView(row, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(6) })
            if (listVisible && player != null && index == currentChannel) row.requestFocus()
        }
        if (start == 0 && player == null) channelGrid.getChildAt(0)?.requestFocus()
        if (end < channels.size) uiHandler.postDelayed({ renderChannelBatch(generation, end) }, 12)
    }

    private fun loadLogo(address: String, image: ImageView) {
        image.tag = address
        logoCache.get(address)?.let { image.setImageBitmap(it); return }
        val startFetch = synchronized(logoLock) {
            val targets = pendingLogoTargets[address]
            if (targets != null) { targets.add(image); false }
            else { pendingLogoTargets[address] = mutableListOf(image); true }
        }
        if (!startFetch) return
        logoIo.execute {
            val bitmap = try {
                val conn = URL(address).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                try {
                    val bytes = conn.getInputStream().use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > 4 * 1024 * 1024) throw IOException("Logo exceeds 4 MB")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sampleSize = 1
                    while (bounds.outWidth / sampleSize > 512 || bounds.outHeight / sampleSize > 320) sampleSize *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                } finally { (conn as? HttpURLConnection)?.disconnect() }
            } catch (_: Exception) { null }
            if (bitmap != null) logoCache.put(address, bitmap)
            val targets = synchronized(logoLock) { pendingLogoTargets.remove(address).orEmpty() }
            runOnUiThread {
                if (bitmap != null && !isFinishing && !isDestroyed) targets.forEach { target ->
                    if (target.tag == address) target.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun playChannel(index: Int) {
        playChannel(index, hideList = true)
    }

    private fun playChannel(index: Int, hideList: Boolean) {
        if (index !in channels.indices) return
        currentChannel = index
        if (player == null) {
            val httpFactory = OkHttpDataSource.Factory(playbackHttpClient)
                .setUserAgent("TV-m3u8/1.0 (Android)")
            val dataSourceFactory = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, httpFactory)) { spec ->
                val host = spec.uri.host.orEmpty()
                if (host.equals("freem3u.xyz", true) || host.endsWith(".freem3u.xyz", true)) {
                    spec.withRequestHeaders(mapOf("Referer" to "https://freem3u.xyz/", "Origin" to "https://freem3u.xyz"))
                } else spec
            }
            val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
            player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build().also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        updatePlaybackQuality(tracks)
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            showPlaybackQuality(videoSize.width, videoSize.height)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val parseError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                        val nextAttempt = playbackMimeAttemptIndex + 1
                        val retryUrl = channels.getOrNull(currentChannel)?.url
                        if (parseError && retryUrl != null && nextAttempt < playbackMimeAttempts.size) {
                            playbackMimeAttemptIndex = nextAttempt
                            val nextMimeType = playbackMimeAttempts[nextAttempt]
                            val retryItem = MediaItem.Builder().setUri(retryUrl).apply {
                                if (nextMimeType != null) setMimeType(nextMimeType)
                            }.build()
                            exo.setMediaItem(retryItem)
                            exo.prepare()
                            exo.playWhenReady = true
                            return
                        }
                        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
                        val certificateFailure = chain.any { cause ->
                            cause is javax.net.ssl.SSLHandshakeException || cause.javaClass.name.contains("CertPath", true) ||
                                cause.javaClass.name.contains("Certificate", true) ||
                                cause.message.orEmpty().contains("trust anchor", true) || cause.message.orEmpty().contains("certificate", true)
                        }
                        val host = Uri.parse(channels.getOrNull(currentChannel)?.url.orEmpty()).host.orEmpty()
                        val message = if (certificateFailure) {
                            "Chứng chỉ HTTPS của ${host.ifBlank { "máy chủ kênh" }} không được thiết bị tin cậy. Kiểm tra ngày giờ Android TV; nếu vẫn lỗi, máy chủ kênh cần sửa chứng chỉ."
                        } else {
                            val reason = chain.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) } ?: error.errorCodeName
                            "Không phát được kênh: $reason"
                        }
                        runOnUiThread { toast(message) }
                    }
                })
                video.player = exo
            }
        }
        val address = channels[index].url
        selectedVideoBitrateKbps = null
        uiHandler.removeCallbacks(hidePlaybackQuality)
        qualityInfo.visibility = View.GONE
        if (address.startsWith("udp://", true) || address.startsWith("webrtc://", true)) {
            toast(if (address.startsWith("udp://", true)) "Chưa hỗ trợ UDP trực tiếp. Hãy dùng HLS, DASH hoặc RTSP." else "WebRTC cần thông tin signaling riêng, không thể phát chỉ bằng URL trong M3U.")
            return
        }
        val path = address.substringBefore('?').lowercase(Locale.ROOT)
        val preferredMime = when {
            address.startsWith("rtsp://", true) -> MimeTypes.APPLICATION_RTSP
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        playbackMimeAttempts = if (address.startsWith("rtsp://", true) || address.startsWith("rtmp://", true)) {
            listOf(preferredMime)
        } else if (preferredMime == MimeTypes.APPLICATION_M3U8) {
            listOf(preferredMime, MimeTypes.APPLICATION_MPD, null)
        } else if (preferredMime == MimeTypes.APPLICATION_MPD) {
            listOf(preferredMime, MimeTypes.APPLICATION_M3U8, null)
        } else {
            listOf(null, MimeTypes.APPLICATION_M3U8, MimeTypes.APPLICATION_MPD)
        }
        playbackMimeAttemptIndex = 0
        val mediaItem = MediaItem.Builder().setUri(address).apply {
            val initialMimeType = playbackMimeAttempts.firstOrNull()
            if (initialMimeType != null) setMimeType(initialMimeType)
        }.build()
        if (address.startsWith("rtsp://", true)) {
            val rtspSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setSocketFactory(playbackSslContext.socketFactory)
                .createMediaSource(mediaItem)
            player!!.setMediaSource(rtspSource)
        } else {
            player!!.setMediaItem(mediaItem)
        }
        player!!.prepare(); player!!.playWhenReady = true
        if (hideList) showList(false)
        if (!hideList && listVisible) channelRows.getChildAt(index).requestFocus()
        video.contentDescription = "Đang phát ${channels[index].name}. Nhấn Trở về để về màn hình chính."
    }

    private fun showList(show: Boolean) {
        if (player == null) {
            listVisible = false
            homePanel.visibility = View.VISIBLE
            listPanel.visibility = View.GONE
            if (show && channels.isEmpty()) showSettingsDialog()
            return
        }
        listVisible = show
        listPanel.visibility = if (show) View.VISIBLE else View.GONE
        homePanel.visibility = View.GONE
        if (show) {
            val width = if (player == null) resources.displayMetrics.widthPixels else (resources.displayMetrics.widthPixels - dp(24)).coerceAtMost(dp(420))
            (listPanel.layoutParams as FrameLayout.LayoutParams).also { it.width = width; listPanel.layoutParams = it }
            if (channels.isNotEmpty()) channelRows.getChildAt(currentChannel.coerceIn(0, channels.lastIndex))?.requestFocus()
        }
        if (show && channels.isEmpty()) showSettingsDialog()
    }

    private fun updatePlaybackQuality(tracks: Tracks) {
        val selectedVideoFormats = tracks.groups
            .asSequence()
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter { group.isTrackSelected(it) }
                    .map { group.getTrackFormat(it) }
            }
            .filter { it.width > 0 && it.height > 0 }
            .toList()
        val format = selectedVideoFormats.maxByOrNull { it.width.toLong() * it.height }
        selectedVideoBitrateKbps = format?.bitrate?.takeIf { it > 0 }?.div(1000)
        if (format != null && selectedVideoBitrateKbps != null) {
            showPlaybackQuality(format.width, format.height)
        } else {
            uiHandler.removeCallbacks(hidePlaybackQuality)
            qualityInfo.visibility = View.GONE
        }
    }

    private fun showPlaybackQuality(width: Int, height: Int) {
        if (!::qualityInfo.isInitialized || player == null) return
        val shortSide = minOf(width, height)
        val bitrate = selectedVideoBitrateKbps ?: return
        qualityInfo.text = "${shortSide}p (${bitrate} kbps)"
        qualityInfo.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hidePlaybackQuality)
        uiHandler.postDelayed(hidePlaybackQuality, 10_000L)
    }

    private fun toggleList() = showList(!listVisible)

    private fun changeChannel(delta: Int) {
        if (channels.isEmpty()) return
        val next = if (currentChannel < 0) 0 else (currentChannel + delta + channels.size) % channels.size
        playChannel(next, hideList = !listVisible)
    }

    private fun queueNumericChannelDigit(digit: Int) {
        if (channels.isEmpty()) return
        val maxDigits = channels.size.toString().length.coerceAtLeast(1)
        if (pendingChannelNumber.length >= maxDigits) pendingChannelNumber.clear()
        pendingChannelNumber.append(digit)
        numericChannelOverlay.number = pendingChannelNumber.toString()
        numericChannelOverlay.visibility = View.VISIBLE
        uiHandler.removeCallbacks(numericTuneTimeout)
        when (pendingChannelNumber.length) {
            1 -> uiHandler.postDelayed(numericTuneTimeout, 3000L)
            2 -> uiHandler.postDelayed(numericTuneTimeout, 2000L)
            else -> tunePendingChannelNumber()
        }
    }

    private fun tunePendingChannelNumber() {
        val requestedChannel = pendingChannelNumber.toString().toIntOrNull()
        pendingChannelNumber.clear()
        numericChannelOverlay.visibility = View.GONE
        if (requestedChannel == null || requestedChannel == 0) return
        if (requestedChannel !in 1..channels.size) {
            toast("Playlist hiện có ${channels.size} kênh.")
            return
        }
        playChannel(requestedChannel - 1, hideList = true)
    }

    private fun numericDigitForKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
        KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
        KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
        KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
        else -> -1
    }

    private fun returnToHome() {
        uiHandler.removeCallbacks(hidePlaybackQuality)
        if (::qualityInfo.isInitialized) qualityInfo.visibility = View.GONE
        video.player = null
        player?.apply { playWhenReady = false; stop(); clearMediaItems(); release() }
        player = null
        listVisible = false
        listPanel.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        channelRows.childrenAt().forEach { it.background = channelItemDrawable(false, 12) }
        channelGrid.childrenAt().forEach { it.background = channelItemDrawable(false, 16) }
        if (channelGrid.childCount > 0) channelGrid.getChildAt(currentChannel.coerceIn(0, channelGrid.childCount - 1)).requestFocus()
        else (homePanel.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val digit = numericDigitForKey(event.keyCode)
        if (digit >= 0 && window.decorView.hasWindowFocus()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) queueNumericChannelDigit(digit)
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) changeChannel(1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) changeChannel(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    val focused = currentFocus
                    if (player == null) {
                        if (focused == null || focused === root || !focused.performClick()) if (channels.isEmpty()) showSettingsDialog()
                    } else if (!listVisible) showList(true)
                    else if (focused == null || focused === root || !focused.performClick()) showList(false)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(clockTick)
        uiHandler.removeCallbacks(weatherTick)
        uiHandler.removeCallbacks(numericTuneTimeout)
        uiHandler.removeCallbacks(hidePlaybackQuality)
        synchronized(playlistRequestLock) {
            activePlaylistConnection?.disconnect()
            activePlaylistConnection = null
            activePlaylistTask?.cancel(true)
            activePlaylistTask = null
        }
        video.player = null
        player?.release()
        player = null
        io.shutdownNow()
        playlistIo.shutdownNow()
        logoIo.shutdownNow()
        super.onDestroy()
    }

    override fun onStop() {
        if (player != null) returnToHome()
        super.onStop()
    }

    private fun text(value: String, size: Int, bold: Boolean) = TextView(this).apply { text = value; textSize = size.toFloat(); if (bold) typeface = Typeface.DEFAULT_BOLD }
    private fun button(value: String, size: Int) = Button(this).apply { text = value; textSize = size.toFloat(); isAllCaps = false; minHeight = dp(48); setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.rgb(82, 108, 58)); cornerRadius = dp(10).toFloat() }; stateListAnimator = null }
    private fun focusOutlineDrawable(focused: Boolean, radiusDp: Int, baseStrokeDp: Int) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(radiusDp).toFloat()
        if (focused) setStroke(dp(3), Color.rgb(238, 145, 54)) else if (baseStrokeDp > 0) setStroke(dp(baseStrokeDp), Color.rgb(54, 80, 38))
    }
    private fun actionButtonDrawable(focused: Boolean) = GradientDrawable().apply {
        setColor(Color.rgb(69, 88, 48))
        cornerRadius = dp(12).toFloat()
        if (focused) setStroke(dp(3), Color.rgb(238, 145, 54))
    }
    private fun channelItemDrawable(focused: Boolean, radiusDp: Int) = GradientDrawable().apply {
        setColor(if (focused) Color.rgb(97, 52, 16) else Color.TRANSPARENT)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(if (focused) 3 else 1), if (focused) Color.rgb(239, 155, 68) else Color.rgb(54, 80, 38))
    }
    private fun isDirectMediaUrl(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        val path = lower.substringBefore('?')
        return lower.startsWith("rtsp://") || lower.startsWith("rtmp://") || lower.startsWith("udp://") || lower.startsWith("webrtc://") ||
            listOf(".mpd", ".mp4", ".m4v", ".ts", ".mkv", ".mp3", ".aac", ".flv", ".webm").any { path.endsWith(it) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    private fun android.view.ViewGroup.childrenAt() = (0 until childCount).map { getChildAt(it) }
}
