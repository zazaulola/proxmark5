package io.github.zazaulola.proxmark5.service

import android.content.Context
import android.util.Log
import io.github.zazaulola.proxmark5.ipc.SessionPaths
import io.github.zazaulola.proxmark5.session.ExitMarker
import java.io.IOException

/**
 * The note the service leaves right before it kills its own process. The UI finds it when the binder
 * dies: it is the one dependable sign that the death was on purpose (a callback sent before the kill
 * may be handled after the death notice). A plain write is enough, the page cache outlives the process.
 */
internal object ExitMarkers {
    fun write(app: Context, marker: ExitMarker) {
        try {
            SessionPaths.exitMarker(app).writeText(marker.toJson())
        } catch (e: IOException) {
            Log.w("pm3-service", "cannot write the exit marker", e)
        }
    }
}
