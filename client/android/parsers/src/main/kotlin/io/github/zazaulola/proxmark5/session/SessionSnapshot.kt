package io.github.zazaulola.proxmark5.session

import io.github.zazaulola.proxmark5.parse.ActivityKind
import org.json.JSONException
import org.json.JSONObject

/** What the running command is, as the service sees it. */
data class ActivitySnapshot(
    val kind: ActivityKind = ActivityKind.IDLE,
    val command: String = "",
    val startedAtMs: Long = 0L,
    /** Commands waiting behind the running one. */
    val queued: Int = 0,
    /** Number of this run; changes with every command, so a late Stop can tell which one it meant. */
    val seq: Long = 0L,
    /** Stop or leaving was asked for the running command. */
    val stopping: Boolean = false,
    /** Stop was asked, the command did not end and is not one known to ignore it: offer a restart. */
    val stuck: Boolean = false,
) {
    val running: Boolean get() = kind != ActivityKind.IDLE
}

/**
 * Everything the UI needs to know about the session, in one immutable value. The service publishes a
 * new one on every change and tells the UI with a tiny "changed" call; the UI then reads this. Nothing
 * depends on the order events arrive in, and a lost "changed" is repaired by the next read.
 */
data class SessionSnapshot(
    /** Identifies one service process life (and its log file); random, never repeats in practice. */
    val epoch: Long = 0L,
    val pid: Int = 0,
    /** The client library is loaded and its version is known. */
    val ready: Boolean = false,
    /** Why the client could not start; empty when it did or has not tried yet. */
    val error: String = "",
    val clientVersion: String = "",
    /** Hash of the ARM sources the client was built from; a firmware from the same tree has the same. */
    val clientArmSrc: String = "",
    val link: LinkState = LinkState.IDLE,
    val linkDetail: String = "",
    /** The `hw version` text of the connected device, read once by the service after connecting. */
    val info: String = "",
    /** A firmware write is under way or between its passes: keep the service up, accept no Stop. */
    val flashLease: Boolean = false,
    val activity: ActivitySnapshot = ActivitySnapshot(),
) {
    fun toJson(): String = JSONObject().apply {
        put("v", VERSION)
        put("epoch", epoch)
        put("pid", pid)
        put("ready", ready)
        put("error", error)
        put("clientVersion", clientVersion)
        put("clientArmSrc", clientArmSrc)
        put("link", link.code)
        put("linkDetail", linkDetail)
        put("info", info.take(IpcLimits.MAX_INFO_CHARS))
        put("flashLease", flashLease)
        put("kind", activity.kind.ordinal)
        put("command", activity.command)
        put("startedAtMs", activity.startedAtMs)
        put("queued", activity.queued)
        put("seq", activity.seq)
        put("stopping", activity.stopping)
        put("stuck", activity.stuck)
    }.toString()

    companion object {
        const val VERSION = 1

        /** Reads what [toJson] wrote; null for anything else (an older or newer format, garbage). */
        fun parse(json: String?): SessionSnapshot? {
            if (json.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(json)
                if (o.optInt("v", -1) != VERSION) return null
                SessionSnapshot(
                    epoch = o.optLong("epoch"),
                    pid = o.optInt("pid"),
                    ready = o.optBoolean("ready"),
                    error = o.optString("error"),
                    clientVersion = o.optString("clientVersion"),
                    clientArmSrc = o.optString("clientArmSrc"),
                    link = LinkState.of(o.optInt("link")),
                    linkDetail = o.optString("linkDetail"),
                    info = o.optString("info"),
                    flashLease = o.optBoolean("flashLease"),
                    activity = ActivitySnapshot(
                        kind = ActivityKind.entries.getOrElse(o.optInt("kind")) { ActivityKind.COMMAND },
                        command = o.optString("command"),
                        startedAtMs = o.optLong("startedAtMs"),
                        queued = o.optInt("queued"),
                        seq = o.optLong("seq"),
                        stopping = o.optBoolean("stopping"),
                        stuck = o.optBoolean("stuck"),
                    ),
                )
            } catch (_: JSONException) {
                null
            }
        }
    }
}

/**
 * Written by the service just before it kills its own process, read (and deleted) by the UI when the
 * binder dies. The only dependable "this death was on purpose" signal: a callback sent before the kill
 * may be handled after the death notice.
 */
data class ExitMarker(
    val epoch: Long,
    val pid: Int,
    val reason: String,
    /** The UI may start a new session by itself (a Stop on a command that ignores Enter). */
    val reconnect: Boolean,
) {
    fun toJson(): String = JSONObject().apply {
        put("epoch", epoch)
        put("pid", pid)
        put("reason", reason)
        put("reconnect", reconnect)
    }.toString()

    companion object {
        fun parse(json: String?): ExitMarker? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                if (!o.has("epoch") || !o.has("pid")) return null
                ExitMarker(o.getLong("epoch"), o.getInt("pid"), o.optString("reason"), o.optBoolean("reconnect"))
            } catch (_: JSONException) {
                null
            }
        }
    }
}
