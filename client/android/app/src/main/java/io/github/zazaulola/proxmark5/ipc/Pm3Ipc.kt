package io.github.zazaulola.proxmark5.ipc

/** Names shared by the UI process and the service process. */
object Pm3Ipc {
    private const val PREFIX = "io.github.zazaulola.proxmark5.action."

    /** Intent to Pm3Service sent with startForegroundService: begin a piece of work (see KIND_*). */
    const val ACTION_BEGIN = PREFIX + "BEGIN"

    /** Notification buttons: Stop what runs / leave the session. */
    const val ACTION_STOP = PREFIX + "STOP"
    const val ACTION_EXIT = PREFIX + "EXIT"

    /** Int extra of ACTION_BEGIN. */
    const val EXTRA_KIND = "kind"

    /** String extra of ACTION_BEGIN: the USB device name (UsbDevice.getDeviceName) for KIND_CONNECT. */
    const val EXTRA_DEVICE = "device"

    /** Open the device and connect the client to it. */
    const val KIND_CONNECT = 0

    /** A firmware write is about to start: take the flash lease. */
    const val KIND_FLASH = 1

    /** Run commands without a device (offline commands): keep the process up while they run. */
    const val KIND_RUN = 2
}
