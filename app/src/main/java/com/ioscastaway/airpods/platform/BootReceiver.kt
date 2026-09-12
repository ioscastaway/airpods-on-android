package com.ioscastaway.airpods.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * After a reboot (or an app update) the pods may already be connected, in which case no ACL
 * broadcast is coming to wake [BluetoothEvents]. Check once and start the monitor if so; if the
 * pods connect later, the ACL receiver takes it from there as usual. BOOT_COMPLETED is on the list
 * of allowed reasons to start a foreground service from the background.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!BluetoothEvents.hasConnectPermission(context)) { Log.i(TAG, "${intent.action}: no Bluetooth permission yet"); return }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val pods = withTimeoutOrNull(8_000) { BluetoothEvents.connectedPods(context) }
                if (pods != null) {
                    Log.i(TAG, "${intent.action}: pods already connected; starting monitor")
                    PodsService.start(context, PodsService.Event.CONNECTED)
                } else {
                    Log.i(TAG, "${intent.action}: no pods connected; waiting for the connect broadcast")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object { private const val TAG = "BootReceiver" }
}
