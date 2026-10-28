package io.github.zazaulola.proxmark5.core

/**
 * JNI bindings to the Proxmark client library (libpm3jni.so, see cpp/pm3_jni.c).
 *
 * Only the :pm3 service process loads this. The client is single-threaded: everything here runs on the
 * one thread that called [nativeInit] (pm3-client), except [nativeAbort] and [nativeLinkState], which
 * any thread may call, and [nativeBreak], which belongs to pm3-ctl. The client's stdout and stderr
 * go to the session log file given to [nativeInit]; there is no pipe to read.
 */
object NativeClient {
    init {
        System.loadLibrary("pm3jni")
    }

    /**
     * Sets $HOME, points saved dumps at [dumpDir] (pass "" to keep $HOME), appends the client's
     * stdout and stderr to the file at [logPath] and gives its stdin a pipe for [nativeAbort].
     * Returns 0, or -1 when it failed. Call once; a second call does nothing and returns 0.
     */
    external fun nativeInit(home: String, dumpDir: String, logPath: String): Int

    /** Connects on a client port such as "usb:12" (a usbfs descriptor). */
    external fun nativeOpen(port: String): Boolean

    /** Disconnects. Does not say goodbye to the device, nor wait for it, once the link is dead. */
    external fun nativeClose()
    external fun nativeIsConnected(): Boolean

    /**
     * Link state without any lock, callable from any thread while a command runs:
     * [LINK_NONE], [LINK_UP], or [LINK_DEAD] (the device is gone, only [nativeClose] is left to do).
     * A client command that reconnects (`hw connect`) passes through [LINK_NONE].
     */
    external fun nativeLinkState(): Int

    /**
     * Sends CMD_BREAK_LOOP to the device, like `hw break`. For commands that wait on the device and
     * ignore the keyboard. It can wait for the USB write: call it from pm3-ctl, never from a thread
     * that must not wait. No-op without a live link and while firmware is being written.
     */
    external fun nativeBreak()

    /** Writes an Enter to the client's stdin, which ends the commands that poll the keyboard. Never blocks. */
    external fun nativeAbort()

    /**
     * Throws away the Enters written to the client's stdin and not yet read, such as an abort that
     * came after its command ended. Call it right before [nativeExec], which does not do it itself.
     */
    external fun nativeDrainStdin()

    /** Runs one command line; with grab its ANSI-free output is also kept for [nativeTakeGrabbed]. */
    external fun nativeExec(cmd: String, grab: Boolean): Int

    external fun nativeTakeGrabbed(): ByteArray
    external fun nativeClientVersion(): String

    /** Hash of the ARM sources the client was built from; a firmware from the same tree has the same. */
    external fun nativeClientArmSrc(): String

    /**
     * Every command the client has as UTF-8 text, see io.github.zazaulola.proxmark5.parse.CommandTree.
     * Which commands are available depends on the connected device.
     */
    external fun nativeCommandTree(): ByteArray

    /**
     * Flashes ELF [files] on [port], like `pm3 --flash`. Returns 0, a negative PM3 error, or
     * [FLASH_REOPEN_PORT]: the device went to its bootloader, a new USB device, so open that
     * one and call again with the same files. [force] skips the client/firmware version check.
     */
    external fun nativeFlash(port: String, files: Array<String>, unlockBootloader: Boolean, force: Boolean): Int

    /** Writes a raw image through the PM5 AT32 ROM DFU loader, like `pm3 --dfu`. 0 on success. */
    external fun nativeDfuFlash(port: String, file: String, address: Int): Int

    const val LINK_NONE = 0
    const val LINK_UP = 1
    const val LINK_DEAD = 2

    /** client/src/flash.h FLASH_REOPEN_PORT */
    const val FLASH_REOPEN_PORT = 1

    /** client/src/dfuse.h DFUSE_DEFAULT_ADDRESS */
    const val DFU_ADDRESS = 0x08000000
}
