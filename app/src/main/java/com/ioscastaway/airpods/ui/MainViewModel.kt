package com.ioscastaway.airpods.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ioscastaway.airpods.platform.BluetoothEvents
import com.ioscastaway.airpods.platform.PodsService
import com.ioscastaway.airpods.platform.PodsStore
import com.ioscastaway.airpods.pods.EarDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val store = PodsStore.get(app)

    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName.asStateFlow()

    fun refreshConnected() {
        viewModelScope.launch {
            _connectedName.value = BluetoothEvents.connectedPods(getApplication())?.let {
                runCatching { it.name }.getOrNull()
            }
        }
    }

    fun startMonitoring() = PodsService.start(getApplication(), PodsService.Event.MANUAL)
    fun stopMonitoring() = PodsService.stop(getApplication())

    fun setAutoPause(on: Boolean) = store.update { it.copy(autoPause = on) }
    fun setMode(mode: EarDetector.Mode) = store.update { it.copy(mode = mode) }
    fun setPopup(on: Boolean) = store.update { it.copy(popup = on) }
}
