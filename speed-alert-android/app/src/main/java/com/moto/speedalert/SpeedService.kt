package com.moto.speedalert

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service that keeps reading GPS with the screen off, works out the speed
 * limit and beeps when over limit + tolerance.
 */
class SpeedService : Service(), LocationListener {

    private lateinit var settings: Settings
    private lateinit var beeper: Beeper
    private lateinit var cameras: CameraDb
    private val roads = RoadFetcher()
    private val detector = OverspeedDetector()
    private val resolver = LimitResolver()
    private val cameraAlertedAt = HashMap<Camera, Long>()
    private var lastFix: Location? = null
    private var lastHeading: Double? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        beeper = Beeper(this).also { it.volume = settings.volume / 100f }
        cameras = assets.open("cameras.csv").reader(Charsets.UTF_8).use { CameraDb.parse(it) }
        createChannel()
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SETTINGS_CHANGED) {
            beeper.volume = settings.volume / 100f
            return START_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("準備中，等待 GPS 定位…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoSpeedAlert:ride")
                .also { it.acquire() }
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            AppState.update(RideState(status = "沒有定位權限"))
            stopSelf()
            return START_NOT_STICKY
        }
        AppState.update(AppState.state.copy(running = true, hasFix = false, status = "等待 GPS 定位…"))
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        val now = System.currentTimeMillis()
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else speedFromPrevious(loc)
        val heading = headingOf(loc, speedKmh)
        lastFix = loc

        val cameraHit = if (heading != null) {
            cameras.nearestAhead(loc.latitude, loc.longitude, heading, settings.cameraDistance.toDouble())
        } else null

        val roadMatch = if (settings.autoLimit) {
            Roads.match(roads.roadsAt(loc.latitude, loc.longitude), loc.latitude, loc.longitude, heading)
        } else null

        val resolved = resolver.resolve(
            now = now,
            auto = settings.autoLimit,
            defaultLimit = settings.defaultLimit,
            camera = cameraHit,
            cameraLimitRange = CAMERA_LIMIT_RANGE,
            road = roadMatch,
        )
        val threshold = resolved.limit + settings.tolerance
        val over = detector.update(speedKmh, threshold)

        if (cameraHit != null && settings.cameraAlert) {
            val prev = cameraAlertedAt[cameraHit.camera]
            if (prev == null || now - prev > CAMERA_REALERT_MS) {
                cameraAlertedAt[cameraHit.camera] = now
                beeper.cameraAlert()
            }
        }
        beeper.setOverspeed(over)

        val status = when {
            !settings.autoLimit -> "手動限速模式"
            !roads.hasData && roads.lastError != null -> "道路資料下載失敗，使用預設限速（${roads.lastError}）"
            !roads.hasData -> "下載附近道路資料中…"
            else -> "GPS 精度 ±${loc.accuracy.toInt()} m"
        }
        AppState.update(
            RideState(
                running = true,
                hasFix = true,
                speedKmh = speedKmh,
                limit = resolved.limit,
                limitSource = resolved.source,
                threshold = threshold,
                overspeed = over,
                roadName = roadMatch?.road?.name,
                camera = cameraHit,
                status = status,
            )
        )
        val text = "時速 ${speedKmh.toInt()}｜限速 ${resolved.limit}" + if (over) "｜超速！" else ""
        if (text != lastNotifText) {
            lastNotifText = text
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun speedFromPrevious(loc: Location): Double {
        val prev = lastFix ?: return 0.0
        val dt = (loc.time - prev.time) / 1000.0
        if (dt <= 0) return 0.0
        return prev.distanceTo(loc) / dt * 3.6
    }

    /** Travel direction; null when too slow for it to mean anything. */
    private fun headingOf(loc: Location, speedKmh: Double): Double? {
        if (speedKmh < MIN_HEADING_SPEED) return lastHeading.takeIf { speedKmh > 1 }
        val h = if (loc.hasBearing()) {
            loc.bearing.toDouble()
        } else {
            val prev = lastFix ?: return lastHeading
            if (prev.distanceTo(loc) < 3) return lastHeading
            Geo.bearing(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
        }
        lastHeading = h
        return h
    }

    override fun onProviderDisabled(provider: String) {
        AppState.update(AppState.state.copy(hasFix = false, status = "GPS 已關閉，請開啟定位"))
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onDestroy() {
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        beeper.release()
        roads.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        detector.reset()
        resolver.reset()
        AppState.update(RideState(running = false, status = "已停止"))
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "測速提醒", NotificationManager.IMPORTANCE_LOW)
        ch.setSound(null, null)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpeedService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("機車超速提醒 運作中")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.moto.speedalert.STOP"
        const val ACTION_SETTINGS_CHANGED = "com.moto.speedalert.SETTINGS"
        private const val CHANNEL = "ride"
        private const val NOTIF_ID = 1
        private const val MIN_HEADING_SPEED = 5.0 // km/h
        private const val CAMERA_LIMIT_RANGE = 200.0 // m: use the camera's posted limit this close
        private const val CAMERA_REALERT_MS = 5 * 60_000L
    }
}
