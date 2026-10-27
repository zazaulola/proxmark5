package io.github.zazaulola.proxmark5.core

import android.app.Application

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
