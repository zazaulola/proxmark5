package io.github.zazaulola.proxmark5.core

import android.os.ParcelFileDescriptor
import java.io.FileInputStream

/**
 * JNI bindings to the Proxmark client library (libpm3jni.so, see cpp/pm3_jni.c).
 *
 * The client is single-threaded: every [exec]/[open]/[close] must run on the one
 * thread that called [init]. [ClientController] owns that thread; do not call these
 * from anywhere else. Output is read from the pipe fd [init] returns.
 */
object NativeClient {
    init {
        System.loadLibrary("pm3jni")
    }

    /**
     * Sets $HOME, points saved dumps at [dumpDir] (pass "" to keep $HOME), redirects
     * stdout/stderr to a pipe and returns its read fd (or -1). Call once.
     */
    external fun nativeInit(home: String, dumpDir: String): Int

    /** Connects on a client port such as "usb:12" (a usbfs descriptor). */
    external fun nativeOpen(port: String): Boolean

    external fun nativeClose()
    external fun nativeIsConnected(): Boolean

    /** Runs one command line; with grab its ANSI-free output is also kept for [nativeTakeGrabbed]. */
    external fun nativeExec(cmd: String, grab: Boolean): Int

    external fun nativeTakeGrabbed(): ByteArray
    external fun nativeAbort()
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

    /** client/src/flash.h FLASH_REOPEN_PORT */
    const val FLASH_REOPEN_PORT = 1

    /** client/src/dfuse.h DFUSE_DEFAULT_ADDRESS */
    const val DFU_ADDRESS = 0x08000000

    /**
     * Wraps the pipe read-fd as a stream. Must keep the [ParcelFileDescriptor] reachable
     * for the stream's whole life: a plain FileInputStream(pfd.fileDescriptor) lets the PFD
     * get finalized, and its finalizer closes the fd out from under the reader (so output
     * silently stops after the first GC). AutoCloseInputStream holds the PFD and closes the
     * fd only when the stream itself is closed.
     */
    fun outputStream(fd: Int): FileInputStream =
        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd))
}
