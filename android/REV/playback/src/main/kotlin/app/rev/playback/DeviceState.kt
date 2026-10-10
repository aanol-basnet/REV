package app.rev.playback

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import app.rev.domain.playback.AudioOutput
import app.rev.domain.playback.DeviceSnapshot

/**
 * The phone's situation for the listening log: where the sound goes (speaker, headphones,
 * Bluetooth, the car, a Cast device) and that device's name, the connection, charging, the screen
 * and the media volume. Needs no permissions; anything unavailable is left out.
 */
internal class DeviceState(
    context: Context,
    private val player: Player,
    private val inCar: () -> Boolean,
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)

    fun snapshot(): DeviceSnapshot {
        val (output, name) = output()
        return DeviceSnapshot(
            output = output,
            outputName = name,
            network = network(),
            charging = charging(),
            screenOn = power?.isInteractive,
            volume = volume(),
        )
    }

    private fun output(): Pair<AudioOutput, String?> {
        if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) return AudioOutput.Cast to null
        val devices = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        fun first(types: Set<Int>) = devices.firstOrNull { it.type in types }
        val bluetooth = first(BLUETOOTH)
        val wired = first(WIRED)
        val output = when {
            inCar() -> AudioOutput.Car
            bluetooth != null -> AudioOutput.Bluetooth
            wired != null -> AudioOutput.Headphones
            else -> AudioOutput.Speaker
        }
        val name = (bluetooth ?: wired)?.productName?.toString()?.takeIf { it.isNotBlank() && output != AudioOutput.Speaker }
        return output to name
    }

    private fun network(): String? {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun charging(): Boolean? {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun volume(): Double? {
        val manager = audio ?: return null
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return null
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max
    }

    private companion object {
        val BLUETOOTH: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
        val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
