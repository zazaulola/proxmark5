package io.github.zazaulola.proxmark5.service

import android.util.Log
import java.util.concurrent.ThreadFactory

/**
 * What the service's threads do with an exception nobody caught: log it and end there. The default
 * handler would take the whole process down, and with it the session.
 */
internal val logUncaught = Thread.UncaughtExceptionHandler { t, e -> Log.e("pm3-service", "uncaught in ${t.name}", e) }

internal fun serviceThreads(name: String) = ThreadFactory { r -> Thread(r, name).apply { uncaughtExceptionHandler = logUncaught } }

/** A callback payload that carries nothing: a failure answer, or an output nobody asked for. */
internal val NO_BYTES = ByteArray(0)
