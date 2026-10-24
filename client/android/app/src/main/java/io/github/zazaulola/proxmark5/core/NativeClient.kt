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

    /** Sets $HOME, redirects stdout/stderr to a pipe, returns its read fd (or -1). Call once. */
    external fun nativeInit(home: String): Int

    /** Connects on a client port such as "usb:12" (a usbfs descriptor). */
    external fun nativeOpen(port: String): Boolean

    external fun nativeClose()
    external fun nativeIsConnected(): Boolean

    /** Runs one command line; with grab its ANSI-free output is also kept for [nativeTakeGrabbed]. */
    external fun nativeExec(cmd: String, grab: Boolean): Int

    external fun nativeTakeGrabbed(): ByteArray
    external fun nativeAbort()
    external fun nativeClientVersion(): String

    /** Wraps the pipe read-fd so it can be read with ordinary streams. */
    fun outputStream(fd: Int): FileInputStream =
        ParcelFileDescriptor.adoptFd(fd).let { FileInputStream(it.fileDescriptor) }
}
