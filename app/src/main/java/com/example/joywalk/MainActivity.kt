package com.example.joywalk

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var coordsInput: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun text(s: String, size: Float = 15f) =
            root.addView(TextView(this).apply { text = s; textSize = size; setPadding(0, pad / 2, 0, pad / 2) })

        fun button(label: String, onClick: () -> Unit) =
            root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })

        text("JoyWalk", 26f)
        text(
            "One-time setup:\n" +
            "1. Turn on Developer options: Settings › About phone › tap \"Build number\" 7 times.\n" +
            "2. In Developer options › \"Select mock location app\" › choose JoyWalk.\n" +
            "3. Allow JoyWalk to display over other apps.\n" +
            "4. (Rooted phones) In LSPosed, enable the JoyWalk module and tick the apps that " +
            "should not see the location as fake, then force-stop those apps."
        )
        button("Open Developer options") {
            open(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
        button("Allow display over other apps") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        text("Start point — paste \"lat, lng\" (long-press a spot in Google Maps to copy it):")
        coordsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "39.739236, -104.990251"
        }
        root.addView(coordsInput)

        button("Start  (or jump here if running)") { start() }
        button("Stop  —  restore real GPS") {
            stopService(Intent(this, SpoofService::class.java))
            refreshStatus()
        }

        status = TextView(this).apply { setPadding(0, pad, 0, 0) }
        root.addView(status)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val saved = getSharedPreferences(SpoofService.PREFS, MODE_PRIVATE)
            .getString(SpoofService.PREF_COORDS, null)
        if (saved != null && !SpoofService.running) coordsInput.setText(saved)
        refreshStatus()
    }

    private fun refreshStatus() {
        val run = if (SpoofService.running)
            "● Running — use the floating joystick. Stop it here or from the notification."
        else
            "○ Not running."
        val hide = if (ModuleStatus.isActive())
            "✓ Mock flag hidden from the apps ticked in LSPosed."
        else
            "✗ Apps can see the location is mocked (needs root + LSPosed, step 4)."
        status.text = "$run\n\n$hide"
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open that screen — find it in Settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun start() {
        val parts = coordsInput.text.toString().split(",").map { it.trim() }
        val lat = parts.getOrNull(0)?.toDoubleOrNull()
        val lng = parts.getOrNull(1)?.toDoubleOrNull()
        if (parts.size != 2 || lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            status.text = "Enter coordinates like: 39.739236, -104.990251"
            return
        }

        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(needed.toTypedArray(), 1)
            return
        }
        if (!isMockLocationApp()) {
            status.text = "JoyWalk isn't the mock location app yet — see step 2 above."
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            status.text = "Allow \"display over other apps\" — see step 3 above."
            return
        }

        startForegroundService(
            Intent(this, SpoofService::class.java)
                .putExtra(SpoofService.EXTRA_LAT, lat)
                .putExtra(SpoofService.EXTRA_LNG, lng)
        )
        status.postDelayed({ refreshStatus() }, 500)
        moveTaskToBack(true)   // get out of the way so you can open your map/game
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            status.text = "Location permission is required to run the spoofing service."
        }
    }

    @Suppress("DEPRECATION")
    private fun isMockLocationApp(): Boolean = try {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), packageName)
        else
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, android.os.Process.myUid(), packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        true  // can't tell; let the service try and report
    }
}
