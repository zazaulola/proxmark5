package io.github.zazaulola.proxmark5.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Proxmark USB ids, from common_arm/usb/usb_cdc_desc.c and the AT32 ROM DFU loader. */
object Pm3Ids {
    const val CDC_VID = 0x9ac4
    const val CDC_PID = 0x4b8f
    const val DFU_VID = 0x2e3c
    const val DFU_PID = 0xdf11

    fun isProxmark(d: UsbDevice) =
        (d.vendorId == CDC_VID && d.productId == CDC_PID) ||
            (d.vendorId == DFU_VID && d.productId == DFU_PID)

    fun isDfu(d: UsbDevice) = d.vendorId == DFU_VID && d.productId == DFU_PID
}

/** An open USB device: the client port to pass to the native layer and the handle to close. */
class UsbSession(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {
    /** "usb:<fd>" — the usbfs descriptor the client reads (uart_posix.c / usbfs_linux.c). */
    val port: String get() = "usb:${connection.fileDescriptor}"
    val isDfu get() = Pm3Ids.isDfu(device)

    fun close() = connection.close()
}

/**
 * Finds the Proxmark on the OTG bus, asks for permission once, and opens it so the
 * client can talk to it over usbfs. No root and no Termux: the app gets the file
 * descriptor straight from [UsbManager].
 */
class UsbTransport(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findProxmark(): UsbDevice? = manager.deviceList.values.firstOrNull { Pm3Ids.isProxmark(it) }

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    /** Opens the device; the CDC data interface is claimed natively by the client. */
    fun open(device: UsbDevice): UsbSession? {
        val conn = manager.openDevice(device) ?: return null
        return UsbSession(device, conn)
    }

    /** Asks the user for permission to the device; resumes with the granted device or null. */
    suspend fun requestPermission(device: UsbDevice): UsbDevice? =
        suspendCancellableCoroutine { cont ->
            val action = "io.github.zazaulola.proxmark5.USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != action) return
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(if (granted) device else null)
                }
            }
            val flags = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, flags, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, flags)
            }
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent(action).setPackage(context.packageName), piFlags
            )
            manager.requestPermission(device, pi)
        }

    fun describe(device: UsbDevice): String {
        val kind = if (Pm3Ids.isDfu(device)) "DFU/загрузчик" else "CDC"
        return "%s (%04x:%04x)".format(kind, device.vendorId, device.productId)
    }

    @Suppress("unused")
    private fun hasCdcData(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) return true
        }
        return false
    }
}
