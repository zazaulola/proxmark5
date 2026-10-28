package io.github.zazaulola.proxmark5.remote

/** Things the session tells the UI once, as opposed to what it keeps true in [Pm3Session.state]. */
sealed interface SessionEvent {
    /** The service was restarted on purpose (a hard Stop of a stubborn command); the UI may connect again. Only sent while the UI is visible. */
    data object ReconnectAfterRestart : SessionEvent

    /** The service process died without warning. */
    data class ServiceDied(val reason: String) : SessionEvent
}
