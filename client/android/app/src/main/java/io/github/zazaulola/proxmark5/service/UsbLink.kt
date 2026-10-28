package io.github.zazaulola.proxmark5.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.zazaulola.proxmark5.usb.Pm3Ids

/**
 * The service's own hold on the USB device. It opens the device by name (the permission belongs to
 * the app's uid, so the service process may use what the UI process asked for), keeps the
 * [UsbDeviceConnection] in a field for as long as the client uses its descriptor, and closes it
 * only when told to, which is after the client let go. Used from the client thread.
 */
internal class UsbLink(private val app: Context) {
    /** What the device is opened for: the client talks to the Proxmark, or a flasher writes the bootloader/firmware. */
    enum class Purpose { CONNECT, FLASH, DFU }

    /** [retry]: the failure may be gone in a moment (the interface of a killed client lingers for a while). */
    class OpenException(override val message: String, val retry: Boolean) : Exception(message)

    private val manager = app.getSystemService(Context.USB_SERVICE) as UsbManager

    // held by a field: once the connection is collected, its finalizer closes the descriptor under the client
    private var connection: UsbDeviceConnection? = null

    /** Opens the device [name] and returns the client port for it ("usb:<fd>"). */
    fun open(name: String, purpose: Purpose): String {
        val device = manager.deviceList[name]
            ?: throw OpenException("Устройство не найдено, возможно, его отключили", retry = false)
        if (!Pm3Ids.isProxmark(device)) throw OpenException("Это не Proxmark", retry = false)
        val dfu = Pm3Ids.isDfu(device)
        if (purpose == Purpose.DFU && !dfu) {
            throw OpenException("Устройство не в режиме DFU: зажмите кнопку и подключите кабель", retry = false)
        }
        if (purpose != Purpose.DFU && dfu) {
            throw OpenException("Устройство в режиме DFU (загрузчик МК): нужно восстановление через DFU", retry = false)
        }
        if (!manager.hasPermission(device)) {
            throw OpenException("Нет доступа к USB-устройству: разрешите его в окне Android", retry = false)
        }
        check(connection == null) { "the previous USB connection is still open" }
        val opened = try {
            manager.openDevice(device)
        } catch (e: RuntimeException) {
            throw OpenException("Не удалось открыть USB-устройство: ${e.message}", retry = false)
        }
        if (opened == null) throw OpenException("Не удалось открыть USB-устройство", retry = true)
        connection = opened
        return "usb:${opened.fileDescriptor}"
    }

    /** Releases the device. Call it only after the client closed its side (nativeClose, or nativeFlash returned). */
    fun close() {
        val held = connection ?: return
        connection = null
        held.close()
    }

    /**
     * Tells [onDetached] the name of every USB device that is pulled out. The receiver only reports:
     * what to do about it is for the host thread to decide. Registered for the life of the process.
     */
    fun watchDetach(onDetached: (String) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (device != null) onDetached(device.deviceName)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
