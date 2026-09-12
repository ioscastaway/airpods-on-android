package com.ioscastaway.airpods.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.content.IntentFilter
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
import com.ioscastaway.airpods.BuildConfig
import com.ioscastaway.airpods.R
import com.ioscastaway.airpods.pods.AapParser
import com.ioscastaway.airpods.pods.EarDetector
import com.ioscastaway.airpods.pods.PodsModel
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
 * Foreground service that runs while AirPods are connected: opens the pods' accessory channel
 * (AAP over classic L2CAP — what the iPhone uses), falls back to their BLE status beacon when that
 * channel is not available, keeps the widget and notification current, drives the
 * connect/disconnect card, and feeds the ear detector that pauses and resumes playback.
 *
 * Two sources, one status: while the accessory channel is open it is the only source (1 % battery,
 * instant ear events) and the BLE scan is switched off to save the radio. If the channel drops,
 * the scan comes back and beacons (10 % steps, when the firmware sends them) take over.
 */
class PodsService : Service(), AapClient.Listener {

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
    private var lastBeaconLogAt = 0L
    private var lastOtherLogAt = 0L

    // Diagnostics: what is actually on the air. Debug builds scan unfiltered and tally it.
    private var advTotal = 0
    private var advApple = 0
    private val appleTypes = HashMap<Int, Int>()
    private var lastSummaryAt = 0L
    private val lastTypeLogAt = HashMap<Int, Long>()

    /**
     * True only between a CONNECTED/MANUAL start and a disconnect. A DISCONNECTED intent can create
     * the service just to show the card, and the audio-routing change that comes with it fires the
     * playback callback; without this flag that callback would start a scan from a service that
     * was never promoted to the foreground.
     */
    private var active = false

    /** Started by hand from the app: keep scanning across disconnects so the lid-open burst is caught. */
    private var manual = false
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
        // Context-registered as well as manifest-registered: a running receiver is never subject to
        // implicit-broadcast limits, so if the stack sends Apple's AT commands at all, this hears them.
        registerReceiver(
            vendorReceiver,
            IntentFilter(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
                addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + ".76")
            },
            RECEIVER_EXPORTED,
        )
        createChannels()
    }

    private val vendorReceiver = HeadsetVendorEvents()

    // ---------------------------------------------------------------- accessory channel (AAP)

    private val aap = AapClient(scope, this)
    private var aapUp = false
    private var aapModel = PodsModel.UNKNOWN
    private var aapBattery: AapParser.Event.Battery? = null
    private var aapEars: Int? = null

    /** Find the connected pods (A2DP may still be coming up right after the ACL broadcast) and open the channel. */
    private fun startAap() {
        scope.launch {
            repeat(4) { attempt ->
                val dev = BluetoothEvents.connectedPods(this@PodsService)
                if (dev != null) { aap.start(dev); return@launch }
                delay(1500)
                if (attempt == 3) Log.i(TAG, "aap: no connected pods")
            }
        }
    }

    override fun onAapLink(up: Boolean) {
        aapUp = up
        store.setAapLink(up)
        if (up) {
            aapBattery = null; aapEars = null
            // The channel is the better source and the radio can idle. Debug builds started by hand
            // keep scanning so the beacon tally (the 0x07 question) continues alongside.
            if (!(manual && BuildConfig.DEBUG)) stopScan()
        } else {
            if (active) applyScanMode()
        }
    }

    override fun onAapEvent(event: AapParser.Event, raw: ByteArray) {
        when (event) {
            is AapParser.Event.Metadata -> {
                aapModel = PodsModel.fromModelNumber(event.modelNumber)
                Log.i(TAG, "aap: ${event.name} ${event.modelNumber} fw=${event.firmware} → $aapModel")
                if (aapBattery != null) publishAap(raw)
            }
            is AapParser.Event.Battery -> { aapBattery = event; publishAap(raw) }
            is AapParser.Event.Ear -> {
                aapEars = event.inEarCount
                Log.i(TAG, "aap: ear ${event.primary}/${event.secondary} → $aapEars in")
                publishAap(raw)
            }
            is AapParser.Event.Other -> Unit
        }
    }

    private fun publishAap(raw: ByteArray) {
        val b = aapBattery ?: return
        val now = System.currentTimeMillis()
        val ears = aapEars ?: store.status.value?.takeIf { it.source == PodsStatus.Source.AAP }?.inEarCount ?: 0
        val model = if (aapModel != PodsModel.UNKNOWN) aapModel
            else store.status.value?.model?.takeIf { it != PodsModel.UNKNOWN } ?: PodsModel.UNKNOWN
        fun pct(p: AapParser.Part?) = p?.takeIf { it.state != AapParser.State.DISCONNECTED }?.percent
        fun chg(p: AapParser.Part?) = p?.state == AapParser.State.CHARGING
        val status = PodsStatus(
            modelId = model.id, model = model,
            leftBattery = pct(b.left), rightBattery = pct(b.right), caseBattery = pct(b.case),
            leftCharging = chg(b.left), rightCharging = chg(b.right), caseCharging = chg(b.case),
            inEarLeft = ears >= 1, inEarRight = ears >= 2, flipped = false, rssi = 0, timestampMs = now,
            raw = raw, source = PodsStatus.Source.AAP, earSidesKnown = false,
        )
        val previous = store.status.value
        store.publish(status)
        if (previous == null || previous.source != status.source || changedForDisplay(previous, status)) {
            BatteryWidgetProvider.push(this, status)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        }
        when (detector.onStatus(status, now, stableMs = 0)) {
            EarDetector.Action.PAUSE -> { Log.i(TAG, "ear (aap): pause"); media.pause() }
            EarDetector.Action.RESUME -> { Log.i(TAG, "ear (aap): resume"); media.play() }
            null -> Unit
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val event = intent?.getStringExtra(EXTRA_EVENT)?.let { runCatching { Event.valueOf(it) }.getOrNull() }
        when (event) {
            Event.DISCONNECTED -> {
                if (manual) { Log.i(TAG, "disconnected (manual mode: keep scanning)"); detector.reset(); aap.stop(); return START_STICKY }
                onDisconnected(); return START_NOT_STICKY
            }
            Event.MANUAL -> manual = true
            Event.CONNECTED -> Log.i(TAG, "connected event")
            null -> Unit
        }
        if (!BluetoothEvents.hasScanPermission(this) || !BluetoothEvents.hasConnectPermission(this)) {
            Log.w(TAG, "Bluetooth permissions missing; not starting")
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(store.status.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // A launcher can miss the provider's update after a reinstall; repaint from the last snapshot.
        BatteryWidgetProvider.push(this, store.status.value)
        stopJob?.cancel()
        active = true
        startedAt = SystemClock.elapsedRealtime()
        applyScanMode()
        if (!manual) startWatchdog() else { watchdog?.cancel(); Log.i(TAG, "manual mode: watchdog off") }
        if (BuildConfig.DEBUG) probeClassicBattery()
        startAap()
        if (event == Event.CONNECTED) {
            // The card is only useful with numbers on it: wait briefly for the first fresh status
            // (the accessory channel usually delivers one within a second), then show what we have.
            scope.launch {
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 2500 && (store.status.value?.timestampMs ?: 0L) < t0) delay(100)
                if (store.settings.value.popup) popup.show(connected = true, status = store.status.value)
            }
        }
        return START_STICKY
    }

    private fun onDisconnected() {
        active = false
        rescanJob?.cancel()
        aap.stop()
        stopScan()
        detector.reset()
        if (store.settings.value.popup) popup.show(connected = false, status = store.status.value)
        // Linger briefly so a reconnect (the pods hop between phone and case a lot) does not churn.
        stopJob?.cancel()
        stopJob = scope.launch { delay(4000); stopSelf() }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(vendorReceiver) }
        watchdog?.cancel()
        rescanJob?.cancel()
        runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
        aap.stop()
        stopScan()
        store.setMonitoring(false)
        store.setAapLink(false)
        popup.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * What the classic (HFP) link tells Android about the pods' battery — and whether a third-party
     * app is allowed to read it. getBatteryLevel() and getMetadata() are system APIs; they are not
     * in the public SDK, so this goes through reflection and records exactly how the platform
     * answers: a value, a SecurityException (permission BLUETOOTH_PRIVILEGED), or a blocked lookup.
     */
    private fun probeClassicBattery() {
        scope.launch {
            repeat(4) { attempt ->
                val dev = BluetoothEvents.connectedPods(this@PodsService)
                if (dev == null) {
                    Log.i(TAG, "classic battery probe #$attempt: no connected pods")
                } else {
                    val level = runCatching {
                        dev.javaClass.getMethod("getBatteryLevel").invoke(dev)
                    }.fold({ "value=$it" }, { "blocked: ${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName} ${it.cause?.message ?: it.message}" })
                    fun meta(key: Int): String = runCatching {
                        val raw = dev.javaClass.getMethod("getMetadata", Int::class.javaPrimitiveType).invoke(dev, key) as? ByteArray
                        raw?.let { String(it) } ?: "null"
                    }.fold({ it }, { "blocked(${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName})" })
                    // Keys from BluetoothDevice: 0 manufacturer, 1 model, 5 main battery,
                    // 10/11/12 untethered left/right/case battery, 13 left charging.
                    Log.i(TAG, "classic battery probe #$attempt: getBatteryLevel $level | " +
                        "manufacturer=${meta(0)} model=${meta(1)} main=${meta(5)} L=${meta(10)} R=${meta(11)} case=${meta(12)} Lchg=${meta(13)}")
                }
                delay(5000)
            }
        }
    }

    // ---------------------------------------------------------------- scanning

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) { Log.w(TAG, "scan failed: $errorCode"); scanning = false }
    }

    /** Pick the scan mode for the current situation and (re)start the scan only if it changed. */
    private fun applyScanMode() {
        if (!active) return
        if (aapUp && !(manual && BuildConfig.DEBUG)) { stopScan(); return }
        val warmingUp = SystemClock.elapsedRealtime() - startedAt < WARMUP_MS
        // Manual (diagnostic) mode never drops to balanced: a lid-open burst lasts a second or two,
        // and balanced mode listens only about a quarter of the time.
        val wanted = if (manual || media.isPlaying() || warmingUp) ScanSettings.SCAN_MODE_LOW_LATENCY
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
            // The default reports legacy advertising only. Newer AirPods are Bluetooth 5 devices;
            // include extended PDUs on every PHY so nothing is filtered out before we see it.
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        // Debug builds scan everything and filter in code, so a filter that the controller does not
        // match (or a frame shape this parser does not expect) shows up in the log instead of as silence.
        val filters: List<ScanFilter>? = if (BuildConfig.DEBUG) null else listOf(filter)
        runCatching {
            scanner?.startScan(filters, settings, callback)
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
        val now = System.currentTimeMillis()
        advTotal++
        val data = result.scanRecord?.getManufacturerSpecificData(ProximityParser.APPLE_COMPANY_ID)
        if (data != null && data.isNotEmpty()) {
            advApple++
            val type = data[0].toInt() and 0xFF
            appleTypes.merge(type, 1, Int::plus)
            if (type == 0x07) Log.i(TAG, "PROXIMITY PAIRING FRAME rssi=${result.rssi} hex=" + data.joinToString("") { "%02X".format(it) })
            if (now - (lastTypeLogAt[type] ?: 0L) > 10_000) {
                lastTypeLogAt[type] = now
                Log.d(TAG, "apple type=0x%02X len=%d rssi=%d legacy=%b addr=%s hex=%s".format(
                    type, data.size, result.rssi, result.isLegacy, result.device.address,
                    data.joinToString("") { "%02X".format(it) }))
            }
        }
        if (now - lastSummaryAt > 5000) {
            lastSummaryAt = now
            Log.d(TAG, "adv summary: total=$advTotal apple=$advApple types=" +
                appleTypes.entries.sortedByDescending { it.value }.joinToString(",") { "%02X:%d".format(it.key, it.value) })
        }
        if (data == null) return
        val status = ProximityParser.parse(data, result.rssi, now)
        if (status == null) {
            // Some other Apple message, or a shape this parser does not know yet. Keep it visible.
            if (now - lastOtherLogAt > 5000) {
                lastOtherLogAt = now
                Log.d(TAG, "apple frame type=0x%02X len=%d rssi=%d head=%s".format(
                    data[0].toInt() and 0xFF, data.size, result.rssi, data.take(9).joinToString("") { "%02X".format(it) }))
            }
            return
        }
        if (now - lastBeaconLogAt > 5000) {
            lastBeaconLogAt = now
            Log.d(TAG, "pods frame len=%d rssi=%d model=0x%04X earL=%b earR=%b L=%s R=%s C=%s head=%s".format(
                data.size, result.rssi, status.modelId, status.inEarLeft, status.inEarRight,
                pct(status.leftBattery), pct(status.rightBattery), pct(status.caseBattery), status.rawHex().take(18)))
        }
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
        // The accessory channel is authoritative while it is open; a 10 % beacon must not overwrite 1 % numbers.
        if (aapUp) return

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
