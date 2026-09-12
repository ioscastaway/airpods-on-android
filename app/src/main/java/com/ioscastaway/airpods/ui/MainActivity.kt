package com.ioscastaway.airpods.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ioscastaway.airpods.BuildConfig
import com.ioscastaway.airpods.platform.PodsService
import com.ioscastaway.airpods.widget.WidgetArt
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.ioscastaway.airpods.platform.PodsStore
import com.ioscastaway.airpods.pods.EarDetector
import com.ioscastaway.airpods.pods.PodsStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds: `adb shell am start -n <pkg>/.ui.MainActivity --ez start_monitoring true` starts
        // the monitor without a tap, so a reinstall does not need a hand on the phone.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("start_monitoring", false) == true) {
            PodsService.start(this, PodsService.Event.MANUAL)
        }
        setContent { MaterialTheme { Screen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val status by vm.store.status.collectAsStateWithLifecycle()
    val frames by vm.store.frames.collectAsStateWithLifecycle()
    val settings by vm.store.settings.collectAsStateWithLifecycle()
    val monitoring by vm.store.monitoring.collectAsStateWithLifecycle()
    val connectedName by vm.connectedName.collectAsStateWithLifecycle()
    val headset by vm.store.headsetBattery.collectAsStateWithLifecycle()
    val aapLink by vm.store.aapLink.collectAsStateWithLifecycle()

    var hasBt by remember { mutableStateOf(ctx.has(Manifest.permission.BLUETOOTH_SCAN) && ctx.has(Manifest.permission.BLUETOOTH_CONNECT)) }
    var hasNotif by remember { mutableStateOf(ctx.has(Manifest.permission.POST_NOTIFICATIONS)) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var batteryExempt by remember { mutableStateOf(ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                hasBt = ctx.has(Manifest.permission.BLUETOOTH_SCAN) && ctx.has(Manifest.permission.BLUETOOTH_CONNECT)
                hasNotif = ctx.has(Manifest.permission.POST_NOTIFICATIONS)
                canOverlay = Settings.canDrawOverlays(ctx)
                batteryExempt = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
                if (hasBt) vm.refreshConnected()
            }
        }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        hasBt = r.values.all { it }; if (hasBt) vm.refreshConnected()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasNotif = it }

    Scaffold(topBar = { TopAppBar(title = { Text("Pod Signal") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(status, connectedName, monitoring, aapLink, hasBt,
                onStart = vm::startMonitoring, onStop = vm::stopMonitoring)
            headset?.let { hb ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Headset-profile battery (+IPHONEACCEV)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            (hb.percent?.let { "$it%" } ?: "not reported") +
                                (hb.docked?.let { if (it) " · docked" else "" } ?: "") + " · " + time(hb.atMs),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text("One number for the pair, sent once when the pods connect.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium)
                    Need("Nearby devices (Bluetooth scan + connect)", hasBt) {
                        btLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                    }
                    Need("Notifications (the monitor runs as a foreground service)", hasNotif) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    Need("Display over other apps (the connect card; otherwise a notification)", canOverlay) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }
                    Need("Unrestricted battery (keeps ear detection alive)", batteryExempt) {
                        @Suppress("BatteryLife")
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Behaviour", style = MaterialTheme.typography.titleMedium)
                    Toggle("Pause when a pod comes out, resume when it goes back", settings.autoPause, vm::setAutoPause)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = settings.mode == EarDetector.Mode.ONE_POD, onClick = { vm.setMode(EarDetector.Mode.ONE_POD) }, label = { Text("Either pod") })
                        FilterChip(selected = settings.mode == EarDetector.Mode.BOTH_PODS, onClick = { vm.setMode(EarDetector.Mode.BOTH_PODS) }, label = { Text("Both pods") })
                    }
                    Toggle("Show a card on connect / disconnect", settings.popup, vm::setPopup)
                }
            }

            if (BuildConfig.DEBUG) {
                // Debug only: the widget cells as the launcher will draw them, with sample values.
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Widget preview (debug)", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            WidgetCell(WidgetArt.Kind.LEFT, 100, true, "Left")
                            WidgetCell(WidgetArt.Kind.RIGHT, 63, false, "Right")
                            WidgetCell(WidgetArt.Kind.CASE, 17, false, "Case")
                            WidgetCell(WidgetArt.Kind.CASE, null, false, "Case")
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Beacon inspector", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Raw status frames, newest first. Take a pod out and back in to watch the in-ear bits; " +
                            "this is how the layout gets verified against a firmware.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (frames.isEmpty()) Text("No frames yet.", style = MaterialTheme.typography.bodySmall)
                    frames.take(12).forEach { f ->
                        Text(
                            "${time(f.timestampMs)}  ${f.rssi}dB  ear L${b(f.inEarLeft)}R${b(f.inEarRight)}  " +
                                "L${PodsService.pct(f.leftBattery)} R${PodsService.pct(f.rightBattery)} C${PodsService.pct(f.caseBattery)}" +
                                (if (f.flipped) "  flip" else "") + "\n  " + f.rawHex().take(24),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(
    status: PodsStatus?, connectedName: String?, monitoring: Boolean, aapLink: Boolean, hasBt: Boolean,
    onStart: () -> Unit, onStop: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status?.let { PodsStore.label(it.model, it.modelId) } ?: "No AirPods seen yet", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    connectedName != null -> "Connected for audio: $connectedName"
                    !hasBt -> "Grant Nearby devices to see the connection"
                    else -> "Not connected for audio right now"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (status != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Battery("Left", status.leftBattery, status.leftCharging, if (status.earSidesKnown) status.inEarLeft else null)
                    Battery("Right", status.rightBattery, status.rightCharging, if (status.earSidesKnown) status.inEarRight else null)
                    Battery("Case", status.caseBattery, status.caseCharging, null)
                }
                Text(
                    when (status.source) {
                        PodsStatus.Source.AAP -> "Updated ${time(status.timestampMs)} · accessory channel (1 %) · ${status.inEarCount} in ear"
                        PodsStatus.Source.BEACON -> "Updated ${time(status.timestampMs)} · beacon (10 %) · ${status.rssi} dB"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (monitoring) OutlinedButton(onClick = onStop) { Text("Stop monitoring") }
                else Button(onClick = onStart, enabled = hasBt) { Text("Start monitoring") }
                Text(
                    when {
                        aapLink -> "Accessory channel open · beacon scan idle"
                        monitoring -> "Scanning for beacons"
                        else -> "Starts by itself when AirPods connect"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun Battery(label: String, value: Int?, charging: Boolean, inEar: Boolean?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text((value?.let { "$it%" } ?: "—") + if (charging) " ⚡" else "", style = MaterialTheme.typography.titleMedium)
        if (inEar != null) Text(if (inEar) "in ear" else "out", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Need(label: String, ok: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✓ " else "• ") + label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onGrant) { Text("Grant") }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = set)
    }
}

private fun android.content.Context.has(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

private fun b(v: Boolean) = if (v) "1" else "0"
private fun time(ms: Long) = if (ms == 0L) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

@Composable
private fun WidgetCell(kind: WidgetArt.Kind, percent: Int?, charging: Boolean, label: String) {
    val ctx = LocalContext.current
    val bmp = remember(kind, percent, charging) { WidgetArt.cell(ctx, kind, percent, charging) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(bmp.asImageBitmap(), contentDescription = label, modifier = Modifier.size(40.dp))
        Text(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
