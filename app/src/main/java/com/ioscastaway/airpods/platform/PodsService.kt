package com.ioscastaway.airpods.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ioscastaway.airpods.R
import com.ioscastaway.airpods.pods.EarDetector
import com.ioscastaway.airpods.pods.PodsStatus
import com.ioscastaway.airpods.pods.ProximityParser
import com.ioscastaway.airpods.popup.ConnectPopup
import com.ioscastaway.airpods.ui.MainActivity
import com.ioscastaway.airpods.widget.BatteryWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that runs while AirPods are connected: scans for their status beacon, keeps
 * the widget and notification current, drives the connect/disconnect card, and feeds the ear
 * detector that pauses and resumes playback.
 *
 * Why a scan at all when the pods are already connected over classic Bluetooth: the classic link
 * carries audio and the standard headset profile, nothing else. Battery and in-ear state travel
 * on a BLE advertisement Apple designed for iPhones to pick up, and Android can hear it just as
 * well — it only has to be told what the bytes mean.
 */
class PodsService : Service() {

    enum class Event { CONNECTED, DISCONNECTED, MANUAL }

    private lateinit var store: PodsStore
    private lateinit var media: MediaControl
    private lateinit var detector: EarDetector
    private lateinit var popup: ConnectPopup

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false
    private var scanMode = -1
    private var stopJob: Job? = null
    private var rescanJob: Job? = null
    private var watchdog: Job? = null
    private var startedAt = 0L
    private var lastBeaconAt = 0L
    private lateinit var audio: AudioManager

    /**
     * Only playback needs a fast scan: pausing when a pod comes out should feel immediate. With
     * nothing playing, the widget can wait a few seconds for a number, so the radio idles more.
     * Playback changes are the trigger; the decision itself is re-read from isMusicActive() after
     * a short delay, because that flag can lag the callback.
     */
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            rescanJob?.cancel()
            rescanJob = scope.launch { delay(1500); applyScanMode() }
        }
    }

    /** Strongest recent beacon wins; the pods' BLE address is random, so RSSI is the only handle. */
    private var best: Pair<String, Int>? = null
    private var bestSeenAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = PodsStore.get(this)
        media = MediaControl(this)
        popup = ConnectPopup(this)
        detector = EarDetector({ store.settings.value.toDetector() }, { media.isPlaying() })
        audio = getSystemService(AudioManager::class.java)
        audio.registerAudioPlaybackCallback(playbackCallback, Handler(Looper.getMainLooper()))
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val event = intent?.getStringExtra(EXTRA_EVENT)?.let { runCatching { Event.valueOf(it) }.getOrNull() }
        when (event) {
            Event.DISCONNECTED -> { onDisconnected(); return START_NOT_STICKY }
            Event.CONNECTED, Event.MANUAL, null -> Unit
        }
        if (!BluetoothEvents.hasScanPermission(this) || !BluetoothEvents.hasConnectPermission(this)) {
            Log.w(TAG, "Bluetooth permissions missing; not starting")
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(store.status.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        stopJob?.cancel()
        startedAt = SystemClock.elapsedRealtime()
        applyScanMode()
        startWatchdog()
        if (event == Event.CONNECTED) {
            // The first beacon after connecting is what makes the card useful; give it a moment.
            scope.launch {
                delay(1200)
                if (store.settings.value.popup) popup.show(connected = true, status = store.status.value)
            }
        }
        return START_STICKY
    }

    private fun onDisconnected() {
        detector.reset()
        if (store.settings.value.popup) popup.show(connected = false, status = store.status.value)
        // Linger briefly so a reconnect (the pods hop between phone and case a lot) does not churn.
        stopJob?.cancel()
        stopJob = scope.launch { delay(4000); stopSelf() }
    }

    override fun onDestroy() {
        watchdog?.cancel()
        rescanJob?.cancel()
        runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
        stopScan()
        store.setMonitoring(false)
        popup.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- scanning

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed: $errorCode"); scanning = false }
    }

    /** Pick the scan mode for the current situation and (re)start the scan only if it changed. */
    private fun applyScanMode() {
        val warmingUp = SystemClock.elapsedRealtime() - startedAt < WARMUP_MS
        val wanted = if (media.isPlaying() || warmingUp) ScanSettings.SCAN_MODE_LOW_LATENCY
        else ScanSettings.SCAN_MODE_BALANCED
        if (scanning && wanted == scanMode) return
        // Android throttles apps that start scans more than 5 times in 30 s; mode changes are rare
        // (play/pause) and debounced, so a stop/start pair here stays well inside that.
        stopScan()
        startScan(wanted)
        if (warmingUp) {
            rescanJob?.cancel()
            rescanJob = scope.launch { delay(WARMUP_MS); applyScanMode() }
        }
    }

    private fun startScan(mode: Int) {
        if (scanning) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(ProximityParser.APPLE_COMPANY_ID, ProximityParser.PREFIX, ProximityParser.PREFIX_MASK)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setReportDelay(0)
            .build()
        runCatching {
            scanner?.startScan(listOf(filter), settings, callback)
            scanning = true
            scanMode = mode
            store.setMonitoring(true)
            Log.d(TAG, "scan started, mode=" + if (mode == ScanSettings.SCAN_MODE_LOW_LATENCY) "low-latency" else "balanced")
        }.onFailure { Log.w(TAG, "startScan", it) }
    }

    /**
     * A missed disconnect broadcast must not leave the radio scanning all day. If nothing has been
     * heard for a while and no AirPods are connected for audio, the service stops itself.
     */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (true) {
                delay(60_000)
                val quiet = SystemClock.elapsedRealtime() - maxOf(lastBeaconAt, startedAt) > QUIET_LIMIT_MS
                if (quiet && BluetoothEvents.connectedPods(this@PodsService) == null) {
                    Log.i(TAG, "watchdog: no beacons and no audio connection; stopping")
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        runCatching { scanner?.stopScan(callback) }
        scanning = false
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(ProximityParser.APPLE_COMPANY_ID) ?: return
        val now = System.currentTimeMillis()
        val status = ProximityParser.parse(data, result.rssi, now) ?: return
        lastBeaconAt = SystemClock.elapsedRealtime()

        // Several Apple devices may be advertising; follow the strongest one seen in the last 10 s,
        // sticking to the same address while it keeps talking.
        val address = result.device.address
        val mono = SystemClock.elapsedRealtime()
        val current = best
        if (current == null || mono - bestSeenAt > 10_000 || result.rssi > current.second || address == current.first) {
            best = address to result.rssi
            bestSeenAt = mono
        }
        if (best?.first != address) return
        if (result.rssi < MIN_RSSI) return

        val previous = store.status.value
        store.publish(status)
        if (previous == null || changedForDisplay(previous, status)) {
            BatteryWidgetProvider.push(this, status)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        }

        when (detector.onStatus(status, now)) {
            EarDetector.Action.PAUSE -> { Log.i(TAG, "ear: pause"); media.pause() }
            EarDetector.Action.RESUME -> { Log.i(TAG, "ear: resume"); media.play() }
            null -> Unit
        }
    }

    private fun changedForDisplay(a: PodsStatus, b: PodsStatus) =
        a.leftBattery != b.leftBattery || a.rightBattery != b.rightBattery || a.caseBattery != b.caseBattery ||
            a.leftCharging != b.leftCharging || a.rightCharging != b.rightCharging || a.caseCharging != b.caseCharging ||
            a.model != b.model

    // ---------------------------------------------------------------- notification

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, getString(R.string.channel_monitor_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_monitor_description) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, getString(R.string.channel_events_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = getString(R.string.channel_events_description) }
        )
    }

    private fun notification(status: PodsStatus?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (status == null) getString(R.string.notif_waiting)
        else getString(R.string.notif_battery, pct(status.leftBattery), pct(status.rightBattery), pct(status.caseBattery))
        return NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setContentTitle(status?.let { PodsStore.label(it.model, it.modelId) } ?: getString(R.string.notif_monitoring))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_pods)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "PodsService"
        private const val EXTRA_EVENT = "event"
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_EVENTS = "events"
        private const val NOTIFICATION_ID = 10
        /** Beacons from a pocket a metre away sit around -60; across the room they are noise. */
        private const val MIN_RSSI = -75
        /** Fast scan right after start so the connect card and widget get numbers quickly. */
        private const val WARMUP_MS = 10_000L
        /** No beacon for this long, and no audio connection, means the pods are gone. */
        private const val QUIET_LIMIT_MS = 3 * 60_000L

        fun pct(v: Int?): String = v?.let { "$it%" } ?: "—"

        fun start(context: Context, event: Event) {
            val i = Intent(context, PodsService::class.java).putExtra(EXTRA_EVENT, event.name)
            runCatching { context.startForegroundService(i) }.onFailure { Log.w(TAG, "start", it) }
        }

        /** Deliver an event to a running service without promoting anything to the foreground. */
        fun notify(context: Context, event: Event) {
            val i = Intent(context, PodsService::class.java).putExtra(EXTRA_EVENT, event.name)
            runCatching { context.startService(i) }.onFailure { Log.d(TAG, "notify: service not running") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PodsService::class.java))
        }
    }
}
