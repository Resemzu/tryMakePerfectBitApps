package com.example.bitperfect.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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

class UsbDacDetector(private val context: Context) {

    companion object {
        private const val TAG = "BitPerfectEngine"
        const val ACTION_USB_PERMISSION = "com.example.bitperfect.ACTION_USB_PERMISSION"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _isUsbDacConnected = MutableStateFlow(false)
    val isUsbDacConnected: StateFlow<Boolean> = _isUsbDacConnected.asStateFlow()

    private val _dacFormatInfo = MutableStateFlow(DacFormatInfo())
    val dacFormatInfo: StateFlow<DacFormatInfo> = _dacFormatInfo.asStateFlow()

    private val _directUsbFd = MutableStateFlow(-1)
    val directUsbFd: StateFlow<Int> = _directUsbFd.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            checkUsbDacDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            checkUsbDacDevices()
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(cntx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.d(TAG, "USB Permission Granted for Direct Pass-Through: ${it.productName}")
                            openDirectUsbConnection(it)
                        }
                    } else {
                        Log.w(TAG, "USB Permission Denied for Direct Pass-Through: ${device?.productName}")
                    }
                }
            }
        }
    }

    init {
        registerCallback()
        registerUsbReceiver()
        checkUsbDacDevices()
        checkUsbHostDevices()
    }

    fun registerCallback() {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun unregisterCallback() {
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
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
                Log.d(TAG, "Audio device attached: $deviceName, Type: $type")

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
    }

    fun checkUsbHostDevices() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (isUsbAudioDevice(device)) {
                Log.d(TAG, "USB Audio Device detected in Host mode: ${device.productName}")
                if (!usbManager.hasPermission(device)) {
                    val permissionIntent = PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    usbManager.requestPermission(device, permissionIntent)
                } else {
                    openDirectUsbConnection(device)
                }
            }
        }
    }

    private fun isUsbAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        return false
    }

    private fun openDirectUsbConnection(device: UsbDevice) {
        try {
            val connection = usbManager.openDevice(device)
            if (connection != null) {
                val fd = connection.fileDescriptor
                _directUsbFd.value = fd
                Log.d(TAG, "Direct USB File Descriptor obtained for Bit-Perfect Bypass: FD=$fd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open direct USB connection: ${e.message}")
        }
    }

    fun evaluateBitPerfectStatus(isExclusiveMode: Boolean): BitPerfectStatus {
        return when {
            !_isUsbDacConnected.value -> BitPerfectStatus.DISABLED_INTERNAL
            isExclusiveMode -> BitPerfectStatus.ACTIVE_BIT_PERFECT
            else -> BitPerfectStatus.STANDARD_OUTPUT
        }
    }
}
