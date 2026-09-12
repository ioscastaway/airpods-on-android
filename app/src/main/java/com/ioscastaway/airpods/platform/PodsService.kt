package com.ioscastaway.airpods.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
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
    private var stopJob: Job? = null

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
        startScan()
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

    private fun startScan() {
        if (scanning) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        scanner = adapter.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setManufacturerData(ProximityParser.APPLE_COMPANY_ID, ProximityParser.PREFIX, ProximityParser.PREFIX_MASK)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        runCatching {
            scanner?.startScan(listOf(filter), settings, callback)
            scanning = true
            store.setMonitoring(true)
        }.onFailure { Log.w(TAG, "startScan", it) }
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
