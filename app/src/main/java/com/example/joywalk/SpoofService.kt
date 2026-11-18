package com.example.joywalk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

class SpoofService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.joywalk.STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val PREFS = "joywalk"
        const val PREF_COORDS = "coords"

        @Volatile
        var running = false
            private set

        private const val CHANNEL = "joywalk"
        private const val NOTIF_ID = 1
        private const val TICK_MS = 200L          // position update rate (5 Hz)
        private const val METERS_PER_DEG_LAT = 111_320.0

        // label, metres per second at full joystick deflection
        private val SPEEDS = listOf(
            "🚶 Walk" to 1.4,
            "🏃 Jog" to 3.0,
            "🚴 Bike" to 6.0,
            "🚗 Drive" to 15.0,
        )
    }

    private lateinit var lm: LocationManager
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val rng = Random()
    private val providers = mutableListOf<String>()

    private var overlay: View? = null
    private var joystick: JoystickView? = null
    private var coordsText: TextView? = null

    private var lat = 0.0
    private var lng = 0.0
    private var bearing = 0f
    private var speedIdx = 0
    private var lastTick = 0L
    private var lastSave = 0L

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        goForeground()

        // New coordinates = start point, or a teleport if already running.
        val newLat = intent?.getDoubleExtra(EXTRA_LAT, Double.NaN) ?: Double.NaN
        val newLng = intent?.getDoubleExtra(EXTRA_LNG, Double.NaN) ?: Double.NaN
        if (!newLat.isNaN() && !newLng.isNaN()) {
            lat = newLat
            lng = newLng
        }
        if (running) return START_NOT_STICKY

        try {
            setupProviders()
        } catch (e: SecurityException) {
            Toast.makeText(
                this,
                "Set JoyWalk as the mock location app in Developer options first.",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        showOverlay()
        lastTick = SystemClock.elapsedRealtime()
        handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        for (p in providers) {
            runCatching { lm.setTestProviderEnabled(p, false) }
            runCatching { lm.removeTestProvider(p) }
        }
        providers.clear()
        if (lat != 0.0 || lng != 0.0) savePosition()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- location

    @Suppress("DEPRECATION")
    private fun setupProviders() {
        val names = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= 31) names += LocationManager.FUSED_PROVIDER

        for (name in names) {
            runCatching { lm.removeTestProvider(name) }
            try {
                lm.addTestProvider(
                    name,
                    false, false, false, false,  // network, satellite, cell, cost
                    false, true, true,           // altitude, speed, bearing
                    1, 1                         // power LOW, accuracy FINE
                )
                lm.setTestProviderEnabled(name, true)
                providers += name
            } catch (e: SecurityException) {
                throw e                          // not the selected mock app
            } catch (e: Exception) {
                // This provider isn't supported on this device; skip it.
            }
        }
        if (providers.isEmpty()) throw SecurityException("No mock providers available")
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTick) / 1000.0
            lastTick = now

            val jx = joystick?.dx ?: 0f
            val jy = joystick?.dy ?: 0f
            val mag = min(1.0, hypot(jx.toDouble(), jy.toDouble()))
            var speed = 0.0

            if (mag > 0.08) {                    // small dead zone
                speed = SPEEDS[speedIdx].second * mag
                val dist = speed * dt
                val north = dist * (-jy / mag)   // screen up = north
                val east = dist * (jx / mag)
                lat = (lat + north / METERS_PER_DEG_LAT).coerceIn(-89.9, 89.9)
                lng += east / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)))
                if (lng > 180) lng -= 360
                if (lng < -180) lng += 360
                bearing = ((Math.toDegrees(atan2(east, north)) + 360) % 360).toFloat()
            }

            pushLocation(speed)
            coordsText?.text = String.format(
                Locale.US, "%.6f, %.6f  ·  %.1f m/s", lat, lng, speed
            )
            if (now - lastSave > 5_000) {
                savePosition()
                lastSave = now
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun pushLocation(speed: Double) {
        // ~0.5 m of jitter on what apps see, so a standing position looks like real GPS.
        val jitterLat = rng.nextGaussian() * 0.5 / METERS_PER_DEG_LAT
        val jitterLng = rng.nextGaussian() * 0.5 / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)))

        for (p in providers) {
            val loc = Location(p).apply {
                latitude = lat + jitterLat
                longitude = lng + jitterLng
                accuracy = 3f + rng.nextFloat() * 2f
                this.speed = speed.toFloat()
                if (speed > 0) this.bearing = this@SpoofService.bearing
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 10f
            }
            runCatching { lm.setTestProviderLocation(p, loc) }
        }
    }

    private fun savePosition() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_COORDS, String.format(Locale.US, "%.6f, %.6f", lat, lng))
            .apply()
    }

    // ---------------------------------------------------------------- overlay

    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun pill(label: String) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(px(10), px(6), px(10), px(6))
        background = GradientDrawable().apply {
            setColor(0x33FFFFFF)
            cornerRadius = px(12).toFloat()
        }
    }

    private fun showOverlay() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(10), px(6), px(10), px(10))
            background = GradientDrawable().apply {
                setColor(0xCC1E1E1E.toInt())
                cornerRadius = px(18).toFloat()
            }
        }

        val handle = TextView(this).apply {
            text = "⠿  drag to move  ⠿"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, px(4), 0, px(6))
        }

        val stick = JoystickView(this)
        joystick = stick

        val speedBtn = pill(SPEEDS[speedIdx].first)
        speedBtn.setOnClickListener {
            speedIdx = (speedIdx + 1) % SPEEDS.size
            speedBtn.text = SPEEDS[speedIdx].first
        }

        val cruiseBtn = pill("Cruise: off")
        cruiseBtn.setOnClickListener {
            stick.cruise = !stick.cruise
            cruiseBtn.text = if (stick.cruise) "Cruise: ON" else "Cruise: off"
            if (!stick.cruise) stick.center()
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(speedBtn)
            addView(View(context), LinearLayout.LayoutParams(px(6), 1))
            addView(cruiseBtn)
        }

        coordsText = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, px(6), 0, 0)
        }

        panel.addView(handle)
        panel.addView(buttons)
        panel.addView(stick, LinearLayout.LayoutParams(px(150), px(150)).apply { topMargin = px(8) })
        panel.addView(coordsText)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px(16)
            y = px(240)
        }

        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    downX = e.rawX; downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(panel, lp)
                }
            }
            true
        }

        wm.addView(panel, lp)
        overlay = panel
    }

    // ---------------------------------------------------------------- notification

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Location spoofing", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, SpoofService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("JoyWalk is faking your location")
            .setContentText("Stop to get your real GPS back")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stopPi
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
