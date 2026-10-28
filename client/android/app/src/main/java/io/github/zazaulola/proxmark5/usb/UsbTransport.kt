package io.github.zazaulola.proxmark5.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
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

/**
 * Finds the Proxmark on the OTG bus and asks for permission once. It never opens the device:
 * the service (process :pm3) does, with the permission the app already holds, so that its
 * usbfs descriptor dies with the client process and frees the device.
 */
class UsbTransport(private val context: Context) {
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findProxmark(): UsbDevice? = manager.deviceList.values.firstOrNull { Pm3Ids.isProxmark(it) }

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

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
}
