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
