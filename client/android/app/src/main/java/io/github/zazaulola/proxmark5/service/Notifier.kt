package io.github.zazaulola.proxmark5.service

import android.os.RemoteCallbackList
import android.os.RemoteException
import io.github.zazaulola.proxmark5.ipc.IPm3Listener
import io.github.zazaulola.proxmark5.session.SessionIds
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "pm3-events": the only thread that calls the UI. It owns the [RemoteCallbackList], so every
 * broadcast (begin ... finish) happens inside one task of this thread. Every call is oneway and
 * wrapped in a try/catch: a UI that is slow, gone or over its buffer must never stall the service.
 * A DeadObjectException does not mean the UI died, so nothing is unregistered here because of an
 * exception; the list drops a listener when its process really dies.
 *
 * Results go only to the listener of the UI process that sent the command (the owner in the id,
 * see SessionIds). A listener learns its owner from the first call that carries one ([claim]).
 */
internal class Notifier(private val onOwnerGone: (Long) -> Unit, private val onNoListeners: () -> Unit) {
    /** The cookie of a listener; the pm3-events thread only. */
    private class Subscriber {
        var owner = NO_OWNER
    }

    private val executor = Executors.newSingleThreadExecutor(serviceThreads("pm3-events"))
    private val changePending = AtomicBoolean(false)

    private val callbacks: RemoteCallbackList<IPm3Listener> = object : RemoteCallbackList<IPm3Listener>() {
        override fun onCallbackDied(callback: IPm3Listener?, cookie: Any?) {
            val subscriber = cookie as? Subscriber ?: return
            executor.execute {
                ownerMayBeGone(subscriber.owner)
                if (listenerCount() == 0) onNoListeners()
            }
        }
    }

    /** How many UIs are attached. Any thread. */
    fun listenerCount(): Int = callbacks.registeredCallbackCount

    /** Called on a binder thread: the listener must be in the list before the snapshot that attach returns is read. */
    fun register(listener: IPm3Listener) {
        callbacks.register(listener, Subscriber())
    }

    fun unregister(listener: IPm3Listener) {
        executor.execute {
            var owner = NO_OWNER
            each { l, subscriber -> if (l.asBinder() == listener.asBinder()) owner = subscriber.owner }
            callbacks.unregister(listener)
            ownerMayBeGone(owner)
        }
    }

    /** [owner] sent a command: the listeners that do not know their owner yet are its. */
    fun claim(owner: Long) {
        executor.execute { each { _, subscriber -> if (subscriber.owner == NO_OWNER) subscriber.owner = owner } }
    }

    /** The snapshot changed. Several changes in a row make one call. */
    fun changed() {
        if (!changePending.compareAndSet(false, true)) return
        executor.execute {
            changePending.set(false)
            each { l, _ -> l.onChanged() }
        }
    }

    /** The outcome of command [id]; an empty [utf8] is sent when the real one can not be delivered, so the id always completes. */
    fun result(id: Long, code: Int, utf8: ByteArray) {
        val owner = SessionIds.owner(id)
        executor.execute {
            each { l, subscriber ->
                if (subscriber.owner == owner) {
                    try {
                        l.onResult(id, code, utf8)
                    } catch (e: RemoteException) {
                        resend(l, id, code)
                    } catch (e: RuntimeException) {
                        resend(l, id, code)
                    }
                }
            }
        }
    }

    /** The command tree for [id], gzip compressed; empty when there is none. */
    fun tree(id: Long, gzipUtf8: ByteArray) {
        val owner = SessionIds.owner(id)
        executor.execute {
            each { l, subscriber ->
                if (subscriber.owner == owner) {
                    try {
                        l.onTree(id, gzipUtf8)
                    } catch (e: RemoteException) {
                        resendTree(l, id)
                    } catch (e: RuntimeException) {
                        resendTree(l, id)
                    }
                }
            }
        }
    }

    private fun resend(l: IPm3Listener, id: Long, code: Int) {
        try {
            l.onResult(id, code, NO_BYTES)
        } catch (_: RemoteException) {
        } catch (_: RuntimeException) {
        }
    }

    private fun resendTree(l: IPm3Listener, id: Long) {
        try {
            l.onTree(id, NO_BYTES)
        } catch (_: RemoteException) {
        } catch (_: RuntimeException) {
        }
    }

    // pm3-events thread: nobody of this owner is left, so what it queued has no one to be reported to
    private fun ownerMayBeGone(owner: Long) {
        if (owner == NO_OWNER) return
        var present = false
        each { _, subscriber -> if (subscriber.owner == owner) present = true }
        if (!present) onOwnerGone(owner)
    }

    // pm3-events thread only: a broadcast must not overlap another one
    private inline fun each(action: (IPm3Listener, Subscriber) -> Unit) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                val subscriber = callbacks.getBroadcastCookie(i) as? Subscriber ?: continue
                try {
                    action(callbacks.getBroadcastItem(i), subscriber)
                } catch (_: RemoteException) {
                } catch (_: RuntimeException) {
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private companion object {
        /** Real owners are non-negative (an id shifted right), so this can not clash with one. */
        const val NO_OWNER = -1L
    }
}
