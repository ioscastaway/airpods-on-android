package com.ioscastaway.airpods.platform

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.ioscastaway.airpods.BuildConfig
import com.ioscastaway.airpods.pods.AapParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException

/**
 * The channel the iPhone uses: AAP over a classic L2CAP socket on PSM 0x1001.
 *
 * Android has no public constructor for a classic L2CAP socket — `createL2capChannel` and its
 * insecure twin build LE CoC sockets, which the stack drops before anything reaches the air because
 * the pods have no LE link. The BR/EDR constructors, `createL2capSocket` and
 * `createInsecureL2capSocket`, exist on BluetoothDevice but are on the hidden-API blocklist, so
 * plain reflection reports them as missing. HiddenApiBypass lifts that filter for this process
 * only; nothing on the device changes. Measured on a Galaxy Z Fold 8 (One UI 9.0) + AirPods 4
 * (8B39): the insecure socket connects in about 35 ms and the pods answer the handshake at once.
 *
 * Once open the pods push battery (1 % steps), ear state and metadata as they change; this keeps
 * the socket up for as long as the pods are connected and retries with backoff if it drops.
 */
class AapClient(private val scope: CoroutineScope, private val listener: Listener) {

    interface Listener {
        /** Called on the main thread when the channel opens or closes. */
        fun onAapLink(up: Boolean)
        /** Called on the main thread for every decoded packet. */
        fun onAapEvent(event: AapParser.Event, raw: ByteArray)
    }

    @Volatile var connected = false
        private set

    private var job: Job? = null
    @Volatile private var socket: BluetoothSocket? = null

    fun start(device: BluetoothDevice) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run(device) }
    }

    fun stop() {
        job?.cancel(); job = null
        closeSocket()
    }

    private suspend fun run(device: BluetoothDevice) {
        var attempt = 0
        while (scope.isActive && job?.isActive == true) {
            val s = open(device)
            if (s == null) { Log.w(TAG, "no classic L2CAP constructor reachable; giving up"); return }
            socket = s
            try {
                s.connect()
                connected = true
                attempt = 0
                Log.i(TAG, "channel open (${s.javaClass.simpleName})")
                withContext(Dispatchers.Main) { listener.onAapLink(true) }
                val out = s.outputStream
                var batterySeen = false
                // The pods are still initialising right after the link comes up and can drop the
                // notification request — then metadata arrives but battery and ear state never do
                // (seen on lid-open with the pods in the case). LibrePods sends the whole opening
                // sequence three times: at once, at 200 ms and at 5 s. Do the same, plus one extra
                // request at 3 s if no battery has shown up by then.
                val primer = scope.launch(Dispatchers.IO) {
                    fun send(vararg packets: ByteArray) = runCatching { packets.forEach { out.write(it); out.flush() } }
                    send(AapParser.HANDSHAKE, AapParser.SET_FEATURES, AapParser.REQUEST_NOTIFICATIONS)
                    delay(200)
                    send(AapParser.HANDSHAKE, AapParser.SET_FEATURES, AapParser.REQUEST_NOTIFICATIONS)
                    delay(2800)
                    if (!batterySeen) {
                        Log.i(TAG, "no battery after 3 s; asking for notifications again")
                        send(AapParser.REQUEST_NOTIFICATIONS)
                    }
                    delay(2000)
                    send(AapParser.HANDSHAKE, AapParser.SET_FEATURES, AapParser.REQUEST_NOTIFICATIONS)
                }
                val buf = ByteArray(2048)
                val input = s.inputStream
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val packet = buf.copyOf(n)
                        val event = AapParser.parse(packet)
                        if (event is AapParser.Event.Battery) batterySeen = true
                        if (BuildConfig.DEBUG) Log.d(TAG, "rx ${packet.joinToString("") { "%02X".format(it) }.take(64)} → $event")
                        if (event != null) withContext(Dispatchers.Main) { listener.onAapEvent(event, packet) }
                    }
                } finally {
                    primer.cancel()
                }
                Log.i(TAG, "channel closed by peer")
            } catch (e: IOException) {
                if (connected) Log.i(TAG, "channel dropped: ${e.message}") else Log.d(TAG, "connect failed: ${e.message}")
            } finally {
                closeSocket()
                if (connected) {
                    connected = false
                    withContext(Dispatchers.Main) { listener.onAapLink(false) }
                }
            }
            if (job?.isActive != true) return
            attempt++
            if (attempt > MAX_ATTEMPTS) { Log.i(TAG, "stopped retrying after $MAX_ATTEMPTS attempts"); return }
            // A2DP/HFP are still coming up right after the connect broadcast; back off gently.
            delay(minOf(500L shl (attempt - 1), 15_000L))
        }
    }

    private fun closeSocket() {
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    private fun open(device: BluetoothDevice): BluetoothSocket? {
        for (name in listOf("createInsecureL2capSocket", "createL2capSocket")) {
            val s = runCatching {
                HiddenApiBypass.invoke(BluetoothDevice::class.java, device, name, PSM) as BluetoothSocket
            }.onFailure { Log.w(TAG, "$name: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
            if (s != null) return s
        }
        return null
    }

    companion object {
        private const val TAG = "AapClient"
        const val PSM = 0x1001
        private const val MAX_ATTEMPTS = 8

        init {
            // Exempt the Bluetooth package for this process so the BR/EDR L2CAP constructors resolve.
            runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/") }
                .onFailure { Log.w(TAG, "hidden-api exemption failed: ${it.message}") }
        }
    }
}
