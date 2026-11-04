package com.example.bitperfect.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BitPerfectStatus {
    ACTIVE_BIT_PERFECT,  // USB DAC tercolok & Native Engine running in AAUDIO_SHARING_MODE_EXCLUSIVE
    STANDARD_OUTPUT,     // USB DAC tercolok namun engine Shared mode / AudioFlinger
    DISABLED_INTERNAL    // Tidak ada DAC luar yang terdeteksi
}

data class DacFormatInfo(
    val sampleRate: Int = 96000,
    val bitDepth: Int = 24,
    val deviceName: String = "USB DAC"
)

class UsbDacDetector(context: Context) {

    companion object {
        private const val TAG = "UsbDacDetector"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isUsbDacConnected = MutableStateFlow(false)
    val isUsbDacConnected: StateFlow<Boolean> = _isUsbDacConnected.asStateFlow()

    private val _dacFormatInfo = MutableStateFlow(DacFormatInfo())
    val dacFormatInfo: StateFlow<DacFormatInfo> = _dacFormatInfo.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkUsbDacDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkUsbDacDevices()
        }
    }

    init {
        registerCallback()
        checkUsbDacDevices()
    }

    fun registerCallback() {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun unregisterCallback() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    fun checkUsbDacDevices() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var usbDacFound = false
        var sampleRate = 96000
        var bitDepth = 24
        var deviceName = "USB Audio Device"

        for (device in devices) {
            val type = device.type
            if (type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            ) {
                usbDacFound = true
                deviceName = device.productName?.toString() ?: "USB DAC"

                val sampleRates = device.sampleRates
                if (sampleRates.isNotEmpty()) {
                    sampleRate = sampleRates.maxOrNull() ?: 96000
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val encodings = device.encodings
                    if (encodings.contains(AudioFormat.ENCODING_PCM_32BIT)) {
                        bitDepth = 32
                    } else if (encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED)) {
                        bitDepth = 24
                    } else if (encodings.contains(AudioFormat.ENCODING_PCM_16BIT)) {
                        bitDepth = 16
                    }
                }
                break
            }
        }

        _isUsbDacConnected.value = usbDacFound
        _dacFormatInfo.value = DacFormatInfo(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            deviceName = deviceName
        )
        Log.i(TAG, "USB DAC Detected: $usbDacFound ($deviceName - ${bitDepth}bit/${sampleRate}Hz)")
    }

    fun evaluateBitPerfectStatus(isExclusiveMode: Boolean): BitPerfectStatus {
        return when {
            !_isUsbDacConnected.value -> BitPerfectStatus.DISABLED_INTERNAL
            isExclusiveMode -> BitPerfectStatus.ACTIVE_BIT_PERFECT
            else -> BitPerfectStatus.STANDARD_OUTPUT
        }
    }
}
