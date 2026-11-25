package com.moto.speedalert

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var speedView: TextView
    private lateinit var limitView: TextView
    private lateinit var thresholdView: TextView
    private lateinit var sourceView: TextView
    private lateinit var cameraView: TextView
    private lateinit var statusView: TextView
    private lateinit var startStop: Button
    private lateinit var speedPanel: View
    private var testBeeper: Beeper? = null

    private val listener: (RideState) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        speedView = findViewById(R.id.speed)
        limitView = findViewById(R.id.limit)
        thresholdView = findViewById(R.id.threshold)
        sourceView = findViewById(R.id.source)
        cameraView = findViewById(R.id.camera)
        statusView = findViewById(R.id.status)
        startStop = findViewById(R.id.startStop)
        speedPanel = findViewById(R.id.speedPanel)

        startStop.setOnClickListener {
            if (AppState.state.running) stopRide() else startRideWithPermissions()
        }
        buildSettings(findViewById(R.id.settings))
    }

    override fun onStart() {
        super.onStart()
        AppState.listen(listener)
    }

    override fun onStop() {
        AppState.unlisten(listener)
        super.onStop()
    }

    override fun onDestroy() {
        testBeeper?.release()
        super.onDestroy()
    }

    // ---- ride control -------------------------------------------------------

    private fun startRideWithPermissions() {
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
        if (needed.isEmpty()) startRide() else requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startRide()
        } else {
            Toast.makeText(this, "需要「精確位置」權限才能測速", Toast.LENGTH_LONG).show()
        }
    }

    private fun startRide() {
        startForegroundService(Intent(this, SpeedService::class.java))
    }

    private fun stopRide() {
        startService(Intent(this, SpeedService::class.java).setAction(SpeedService.ACTION_STOP))
    }

    private fun notifyServiceSettingsChanged() {
        if (AppState.state.running) {
            startService(Intent(this, SpeedService::class.java).setAction(SpeedService.ACTION_SETTINGS_CHANGED))
        }
    }

    // ---- rendering ----------------------------------------------------------

    private fun render(s: RideState) {
        statusView.text = s.status
        startStop.text = if (s.running) "停止" else "開始"
        startStop.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (s.running) R.color.danger else R.color.accent)
        )
        // Keep the screen on while riding with the app open (phone on the handlebar).
        if (s.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!s.running || !s.hasFix) {
            speedView.text = "--"
            speedView.setTextColor(getColor(R.color.text))
            speedPanel.setBackgroundResource(R.drawable.card)
        } else {
            speedView.text = Math.round(s.speedKmh).toString()
            speedView.setTextColor(getColor(if (s.overspeed) R.color.text else R.color.ok))
            if (s.overspeed) speedPanel.setBackgroundColor(getColor(R.color.danger))
            else speedPanel.setBackgroundResource(R.drawable.card)
        }

        val limit = if (s.running) s.limit else settings.defaultLimit
        val threshold = if (s.running) s.threshold else settings.defaultLimit + settings.tolerance
        limitView.text = limit.toString()
        thresholdView.text = "達到 $threshold km/h 開始嗶嗶警告"
        sourceView.text = if (s.running && s.hasFix) {
            "限速來源：${s.limitSource.label}" + (s.roadName?.let { "｜$it" } ?: "")
        } else ""

        val cam = s.camera
        if (s.running && cam != null) {
            cameraView.visibility = View.VISIBLE
            val kind = if (cam.camera.section) "區間測速" else "測速照相"
            cameraView.text = "📷 前方 ${cam.distance.toInt()} m $kind｜限速 ${cam.camera.limit}\n${cam.camera.address}"
        } else {
            cameraView.visibility = View.GONE
        }
    }

    // ---- settings UI --------------------------------------------------------

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun buildSettings(root: LinearLayout) {
        fun switchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
            root.addView(Switch(this).apply {
                text = label
                textSize = 17f
                setTextColor(getColor(R.color.text))
                isChecked = value
                setPadding(0, dp(10), 0, dp(10))
                setOnCheckedChangeListener { _, c -> onChange(c); render(AppState.state) }
            })
        }

        fun stepperRow(label: String, unit: String, get: () -> Int, set: (Int) -> Unit, step: Int, min: Int, max: Int) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val name = TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(getColor(R.color.text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                textSize = 18f
                setTextColor(getColor(R.color.text))
                gravity = Gravity.CENTER
                minWidth = dp(84)
                text = "${get()} $unit"
            }
            fun button(t: String, delta: Int) = Button(this).apply {
                text = t
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(52))
                setOnClickListener {
                    val v = (get() + delta).coerceIn(min, max)
                    set(v)
                    value.text = "$v $unit"
                    render(AppState.state)
                }
            }
            row.addView(name)
            row.addView(button("−", -step))
            row.addView(value)
            row.addView(button("+", step))
            root.addView(row)
        }

        switchRow("依道路自動切換限速", settings.autoLimit) { settings.autoLimit = it }
        stepperRow("超過限速多少開始警告", "km/h", { settings.tolerance }, { settings.tolerance = it }, 1, 0, 30)
        stepperRow("預設／手動限速", "km/h", { settings.defaultLimit }, { settings.defaultLimit = it }, 10, 20, 110)
        switchRow("測速照相提醒", settings.cameraAlert) { settings.cameraAlert = it }
        stepperRow("照相點提前提醒距離", "m", { settings.cameraDistance }, { settings.cameraDistance = it }, 100, 100, 1000)

        root.addView(TextView(this).apply {
            text = "警報音量"
            textSize = 17f
            setTextColor(getColor(R.color.text))
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(SeekBar(this).apply {
            max = 100
            progress = settings.volume
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) settings.volume = p
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    notifyServiceSettingsChanged()
                    testSound()
                }
            })
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "測試警報聲"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { testSound() }
        })
        buttons.addView(Button(this).apply {
            text = "關閉省電限制"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        })
        root.addView(buttons)
    }

    private fun testSound() {
        val b = testBeeper ?: Beeper(this).also { testBeeper = it }
        b.volume = settings.volume / 100f
        b.testBeep()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已經關閉省電限制", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERMS = 1
    }
}
