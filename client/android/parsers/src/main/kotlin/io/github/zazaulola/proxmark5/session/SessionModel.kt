package io.github.zazaulola.proxmark5.session

/**
 * What the live session is doing with the device, as the service (process :pm3) reports it.
 * The numbers are the wire values in the snapshot; do not renumber.
 */
enum class LinkState(val code: Int) {
    /** No session. */
    IDLE(0),

    /** Opening the USB device and talking to the Proxmark. */
    OPENING(1),
    CONNECTED(2),

    /** Opening failed; the detail says why. */
    FAILED(3),

    /** The cable or the link was lost; the detail says what. */
    LOST(4),

    /** The flasher owns the device. Nothing may interrupt it. */
    FLASHING(5),

    /** Stopping what runs and closing the session. */
    CLOSING(6),

    /** The user left by hand: nothing reconnects until they ask or the cable is plugged in again. */
    LEFT(7);

    /** Sessions in these states keep the foreground service up. */
    val holdsService: Boolean get() = this == OPENING || this == CONNECTED || this == FLASHING || this == CLOSING

    /** A flash may be running or about to: no Stop, no leaving, no killing the process. */
    val uninterruptible: Boolean get() = this == FLASHING

    companion object {
        fun of(code: Int): LinkState = entries.firstOrNull { it.code == code } ?: IDLE
    }
}

/** Result codes of `submit` / `flash` that are not the client's own return values. */
object ResultCode {
    /** Dropped before it ran: Stop, leaving the session or a lost link flushed the queue. */
    const val DROPPED = Int.MIN_VALUE

    /** The service process died or could not be reached: the command's fate is unknown. */
    const val TRANSPORT_LOST = Int.MIN_VALUE + 1

    /** The client was busy with another exclusive call. */
    const val BUSY = -1

    /** True for the codes that mean the command did not get a normal answer. */
    fun isTransport(code: Int) = code == DROPPED || code == TRANSPORT_LOST
}

/** Limits that keep every callback inside what binder can carry (oneway budget is about half of 1 MiB). */
object IpcLimits {
    /** Longest command line the UI sends. */
    const val MAX_COMMAND_CHARS = 16 * 1024

    /** Longest grabbed output a result carries, in UTF-8 bytes. */
    const val MAX_RESULT_BYTES = 96 * 1024

    const val TRUNCATED_MARK = "\n…[обрезано]"

    /** Longest `hw version` text kept in the snapshot. */
    const val MAX_INFO_CHARS = 8 * 1024

    /**
     * Cuts [bytes] to at most [MAX_RESULT_BYTES] on a UTF-8 character boundary and appends the mark
     * when anything was cut.
     */
    fun clampResult(bytes: ByteArray): ByteArray {
        if (bytes.size <= MAX_RESULT_BYTES) return bytes
        val mark = TRUNCATED_MARK.toByteArray(Charsets.UTF_8)
        var end = MAX_RESULT_BYTES - mark.size
        // back off a continuation byte (10xxxxxx) so a glyph is not cut in half
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end) + mark
    }
}

/**
 * Ids of commands. The service outlives the UI, so a new UI process must not be able to receive the
 * result of a command an older one sent: the high bits hold a nonce of the UI process.
 */
object SessionIds {
    private const val COUNTER_BITS = 20
    private const val COUNTER_MASK = (1L shl COUNTER_BITS) - 1

    fun make(nonce: Long, counter: Long): Long = (nonce shl COUNTER_BITS) or (counter and COUNTER_MASK)

    /** Who sent [id]: equal for every id of one UI process. */
    fun owner(id: Long): Long = id ushr COUNTER_BITS
}
