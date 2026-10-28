package io.github.zazaulola.proxmark5.core

import android.app.Application

/**
 * The app has two processes, the UI and the service (":pm3"), and Android creates this class in
 * each of them. So onCreate does nothing that costs time or that only one of them may do: the
 * client library, the service session and the binder connection are all started on demand.
 */
class ClientApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        var instance: ClientApp? = null
            private set
    }
}
