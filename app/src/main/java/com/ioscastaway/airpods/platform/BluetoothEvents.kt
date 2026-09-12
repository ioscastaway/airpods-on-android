package com.ioscastaway.airpods.platform

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The app's entry point when the user is not looking at it.
 *
 * The system delivers ACL connect/disconnect broadcasts to manifest receivers even on modern
 * Android (they are on the implicit-broadcast exemption list), and receiving one is an accepted
 * reason to start a foreground service from the background. So: AirPods connect → this fires →
 * the monitor starts. No polling, no always-on service.
 */
class BluetoothEvents : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        val name = deviceName(context, device)
        Log.d(TAG, "${intent.action} name=$name")
        if (!looksLikePods(name)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> PodsService.start(context, PodsService.Event.CONNECTED)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> PodsService.notify(context, PodsService.Event.DISCONNECTED)
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothProfile.STATE_CONNECTED -> PodsService.start(context, PodsService.Event.CONNECTED)
                    BluetoothProfile.STATE_DISCONNECTED -> PodsService.notify(context, PodsService.Event.DISCONNECTED)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothEvents"

        fun hasConnectPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun hasScanPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

        /** Reading a device name needs BLUETOOTH_CONNECT; without it we can only say "some device". */
        fun deviceName(context: Context, device: BluetoothDevice?): String? =
            if (device != null && hasConnectPermission(context)) runCatching { device.name }.getOrNull() else null

        /**
         * Name-based, and deliberately loose: "AirPods", "AirPods Pro", a renamed "수희의 AirPods".
         * When we cannot read the name at all, say yes and let the beacon scan decide.
         */
        fun looksLikePods(name: String?): Boolean =
            name == null || name.contains("airpods", ignoreCase = true) || name.contains("에어팟")

        /** The AirPods currently connected for audio, if any. */
        suspend fun connectedPods(context: Context): BluetoothDevice? {
            if (!hasConnectPermission(context)) return null
            val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                ?: return null
            return suspendCancellableCoroutine { cont ->
                val ok = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        val found = runCatching {
                            proxy.connectedDevices.firstOrNull { looksLikePods(it.name) && it.name != null }
                        }.getOrNull()
                        adapter.closeProfileProxy(profile, proxy)
                        if (cont.isActive) cont.resume(found)
                    }
                    override fun onServiceDisconnected(profile: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }, BluetoothProfile.A2DP)
                if (!ok && cont.isActive) cont.resume(null)
            }
        }
    }
}
