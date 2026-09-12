package com.ioscastaway.airpods.platform

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Apple's headset-profile battery extension, as Android hands it to third-party apps.
 *
 * Over the classic HFP link an accessory may send vendor AT commands. Apple documents two in its
 * Accessory Design Guidelines: `AT+XAPL` (capabilities handshake) and `+IPHONEACCEV` (key/value
 * pairs; key 1 = battery level 0–9, key 2 = dock state). Android's stack forwards any vendor
 * command to apps that register for ACTION_VENDOR_SPECIFIC_HEADSET_EVENT under the sender's
 * Bluetooth company-id category — 76 is Apple. This is a documented protocol and a public API:
 * category 2 in the series' scale, and the first path here that does not depend on a beacon.
 */
class HeadsetVendorEvents : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT) return
        val cmd = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
        val type = intent.getIntExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, -1)
        @Suppress("DEPRECATION")
        val args = intent.getSerializableExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*>
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        Log.i(TAG, "vendor AT: cmd=$cmd type=$type args=${args?.joinToString(",")} from=${BluetoothEvents.deviceName(context, device)}")

        if (cmd == "+IPHONEACCEV" && args != null) parseAccev(args)?.let { (battery, dock) ->
            Log.i(TAG, "IPHONEACCEV battery=${battery?.let { "$it%" } ?: "—"} docked=${dock ?: "?"}")
            PodsStore.get(context).publishHeadsetBattery(battery, dock)
        }
    }

    companion object {
        private const val TAG = "HeadsetVendor"

        /** `+IPHONEACCEV=count,key1,val1,key2,val2…` — Android hands us everything after the '='. */
        fun parseAccev(args: Array<*>): Pair<Int?, Boolean?>? {
            val n = args.mapNotNull { (it as? Number)?.toInt() ?: it?.toString()?.trim()?.toIntOrNull() }
            if (n.isEmpty()) return null
            var battery: Int? = null
            var dock: Boolean? = null
            var i = 1
            while (i + 1 < n.size) {
                when (n[i]) {
                    1 -> battery = ((n[i + 1].coerceIn(0, 9) + 1) * 10)   // 0–9 → 10–100 %
                    2 -> dock = n[i + 1] == 1
                }
                i += 2
            }
            return battery to dock
        }
    }
}
