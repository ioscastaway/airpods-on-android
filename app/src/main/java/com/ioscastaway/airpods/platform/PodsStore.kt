package com.ioscastaway.airpods.platform

import android.content.Context
import com.ioscastaway.airpods.pods.EarDetector
import com.ioscastaway.airpods.pods.PodsModel
import com.ioscastaway.airpods.pods.PodsStatus
import com.ioscastaway.airpods.pods.ProximityParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings and the last known status, in SharedPreferences, exposed as StateFlows.
 * One instance per process; the service, the widget and the UI all read the same object.
 */
class PodsStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("pods", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(loadStatus())
    val status: StateFlow<PodsStatus?> = _status.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** Last ~40 raw frames, newest first — the beacon inspector reads these. */
    private val _frames = MutableStateFlow<List<PodsStatus>>(emptyList())
    val frames: StateFlow<List<PodsStatus>> = _frames.asStateFlow()

    private val _monitoring = MutableStateFlow(false)
    val monitoring: StateFlow<Boolean> = _monitoring.asStateFlow()

    data class Settings(
        val autoPause: Boolean = true,
        val mode: EarDetector.Mode = EarDetector.Mode.ONE_POD,
        val popup: Boolean = true,
    ) {
        fun toDetector() = EarDetector.Settings(enabled = autoPause, mode = mode)
    }

    private var lastPersistedHex: String? = null
    private var lastPersistedAt = 0L

    /**
     * Beacons arrive several times a second. The in-memory state follows every one; the disk copy
     * is written only when the bytes change or half a minute has passed, and the inspector list
     * skips frames identical to the previous one.
     */
    fun publish(status: PodsStatus) {
        _status.value = status
        val hex = status.rawHex()
        if (_frames.value.firstOrNull()?.rawHex() != hex) {
            _frames.value = (listOf(status) + _frames.value).take(40)
        }
        if (hex != lastPersistedHex || status.timestampMs - lastPersistedAt > 30_000) {
            lastPersistedHex = hex
            lastPersistedAt = status.timestampMs
            prefs.edit()
                .putString(KEY_RAW, hex)
                .putInt(KEY_RSSI, status.rssi)
                .putLong(KEY_TS, status.timestampMs)
                .apply()
        }
    }

    fun setMonitoring(on: Boolean) { _monitoring.value = on }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        _settings.value = s
        prefs.edit()
            .putBoolean(KEY_AUTO_PAUSE, s.autoPause)
            .putString(KEY_MODE, s.mode.name)
            .putBoolean(KEY_POPUP, s.popup)
            .apply()
    }

    private fun loadSettings() = Settings(
        autoPause = prefs.getBoolean(KEY_AUTO_PAUSE, true),
        mode = runCatching { EarDetector.Mode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(EarDetector.Mode.ONE_POD),
        popup = prefs.getBoolean(KEY_POPUP, true),
    )

    private fun loadStatus(): PodsStatus? {
        val hex = prefs.getString(KEY_RAW, null) ?: return null
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return ProximityParser.parse(bytes, prefs.getInt(KEY_RSSI, -99), prefs.getLong(KEY_TS, 0))
    }

    companion object {
        private const val KEY_RAW = "last_raw"
        private const val KEY_RSSI = "last_rssi"
        private const val KEY_TS = "last_ts"
        private const val KEY_AUTO_PAUSE = "auto_pause"
        private const val KEY_MODE = "mode"
        private const val KEY_POPUP = "popup"

        @Volatile private var instance: PodsStore? = null
        fun get(context: Context): PodsStore =
            instance ?: synchronized(this) { instance ?: PodsStore(context.applicationContext).also { instance = it } }

        fun label(model: PodsModel, modelId: Int): String =
            if (model == PodsModel.UNKNOWN) "Apple audio (0x%04X)".format(modelId) else model.label
    }
}
