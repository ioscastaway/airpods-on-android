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

    /** True while the accessory channel (AAP over L2CAP) to the pods is open. */
    private val _aapLink = MutableStateFlow(false)
    val aapLink: StateFlow<Boolean> = _aapLink.asStateFlow()
    fun setAapLink(up: Boolean) { _aapLink.value = up }

    /** Battery as reported over the headset profile (+IPHONEACCEV): one number, at connect time. */
    data class HeadsetBattery(val percent: Int?, val docked: Boolean?, val atMs: Long)
    private val _headsetBattery = MutableStateFlow(loadHeadsetBattery())
    val headsetBattery: StateFlow<HeadsetBattery?> = _headsetBattery.asStateFlow()

    fun publishHeadsetBattery(percent: Int?, docked: Boolean?) {
        val hb = HeadsetBattery(percent, docked, System.currentTimeMillis())
        _headsetBattery.value = hb
        prefs.edit().putInt(KEY_HFP_PCT, percent ?: -1).putLong(KEY_HFP_TS, hb.atMs).apply()
    }

    private fun loadHeadsetBattery(): HeadsetBattery? {
        val ts = prefs.getLong(KEY_HFP_TS, 0L); if (ts == 0L) return null
        return HeadsetBattery(prefs.getInt(KEY_HFP_PCT, -1).takeIf { it >= 0 }, null, ts)
    }

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
            val e = prefs.edit()
                .putString(KEY_SOURCE, status.source.name)
                .putString(KEY_RAW, hex)
                .putInt(KEY_RSSI, status.rssi)
                .putLong(KEY_TS, status.timestampMs)
            if (status.source == PodsStatus.Source.AAP) {
                // The raw bytes are one AAP packet, not a self-contained snapshot; keep the fields too.
                e.putInt(KEY_AAP_MODEL, status.modelId)
                    .putInt(KEY_AAP_L, status.leftBattery ?: -1).putInt(KEY_AAP_R, status.rightBattery ?: -1)
                    .putInt(KEY_AAP_C, status.caseBattery ?: -1)
                    .putBoolean(KEY_AAP_LCHG, status.leftCharging).putBoolean(KEY_AAP_RCHG, status.rightCharging)
                    .putBoolean(KEY_AAP_CCHG, status.caseCharging)
                    .putInt(KEY_AAP_EARS, status.inEarCount)
            }
            e.apply()
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
        val ts = prefs.getLong(KEY_TS, 0)
        if (prefs.getString(KEY_SOURCE, null) == PodsStatus.Source.AAP.name) {
            val modelId = prefs.getInt(KEY_AAP_MODEL, -1)
            val ears = prefs.getInt(KEY_AAP_EARS, 0)
            return PodsStatus(
                modelId = modelId, model = PodsModel.fromId(modelId),
                leftBattery = prefs.getInt(KEY_AAP_L, -1).takeIf { it >= 0 },
                rightBattery = prefs.getInt(KEY_AAP_R, -1).takeIf { it >= 0 },
                caseBattery = prefs.getInt(KEY_AAP_C, -1).takeIf { it >= 0 },
                leftCharging = prefs.getBoolean(KEY_AAP_LCHG, false), rightCharging = prefs.getBoolean(KEY_AAP_RCHG, false),
                caseCharging = prefs.getBoolean(KEY_AAP_CCHG, false),
                inEarLeft = ears >= 1, inEarRight = ears >= 2, flipped = false, rssi = 0, timestampMs = ts, raw = bytes,
                source = PodsStatus.Source.AAP, earSidesKnown = false,
            )
        }
        return ProximityParser.parse(bytes, prefs.getInt(KEY_RSSI, -99), ts)
    }

    companion object {
        private const val KEY_RAW = "last_raw"
        private const val KEY_RSSI = "last_rssi"
        private const val KEY_TS = "last_ts"
        private const val KEY_AUTO_PAUSE = "auto_pause"
        private const val KEY_MODE = "mode"
        private const val KEY_POPUP = "popup"
        private const val KEY_HFP_PCT = "hfp_pct"
        private const val KEY_HFP_TS = "hfp_ts"
        private const val KEY_SOURCE = "last_source"
        private const val KEY_AAP_MODEL = "aap_model"
        private const val KEY_AAP_L = "aap_l"
        private const val KEY_AAP_R = "aap_r"
        private const val KEY_AAP_C = "aap_c"
        private const val KEY_AAP_LCHG = "aap_lchg"
        private const val KEY_AAP_RCHG = "aap_rchg"
        private const val KEY_AAP_CCHG = "aap_cchg"
        private const val KEY_AAP_EARS = "aap_ears"

        @Volatile private var instance: PodsStore? = null
        fun get(context: Context): PodsStore =
            instance ?: synchronized(this) { instance ?: PodsStore(context.applicationContext).also { instance = it } }

        fun label(model: PodsModel, modelId: Int): String =
            if (model == PodsModel.UNKNOWN) "Apple audio (0x%04X)".format(modelId) else model.label
    }
}
