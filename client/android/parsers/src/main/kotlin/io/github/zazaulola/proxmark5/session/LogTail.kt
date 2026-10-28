package io.github.zazaulola.proxmark5.session

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Follows an append-only log file: each [read] returns the bytes added since the last one. The
 * service writes the client's output to the file (and the file survives the client dying), the UI
 * process tails it. Copes with the file not existing yet, being truncated (cleared, or cut by the
 * service because it grew too big) and with a long backlog at the start.
 */
class LogTail(private val file: File, private val maxBacklog: Long = DEFAULT_BACKLOG) {
    /** New bytes. [truncated]: the file shrank and reading restarted at its beginning. */
    class Chunk(val bytes: ByteArray, val truncated: Boolean, val skipped: Long)

    /** Offset of the next byte to be returned. */
    var position: Long = 0L
        private set

    private var started = false

    /** Bytes that are in the file but past [position] right now (0 when unknown or missing). */
    fun pending(): Long = if (file.isFile) maxOf(0L, file.length() - position) else 0L

    /** Up to [max] new bytes, or null when there are none. */
    fun read(max: Int = 64 * 1024): Chunk? {
        require(max > 0) { "max must be positive" }
        if (!file.isFile) return null
        try {
            RandomAccessFile(file, "r").use { f ->
                val len = f.length()
                var truncated = false
                var skipped = 0L
                if (!started) {
                    started = true
                    if (len > maxBacklog) {
                        position = len - maxBacklog
                        skipped = position
                        skipToLineStart = true
                    }
                } else if (len < position) {
                    position = 0L
                    truncated = true
                }
                if (len <= position) return null
                f.seek(position)
                val buf = ByteArray(minOf(max.toLong(), len - position).toInt())
                val n = f.read(buf)
                if (n <= 0) return null
                position += n
                var out = if (n == buf.size) buf else buf.copyOf(n)
                if (skipToLineStart) {
                    // the cut may be inside a line or a glyph: drop up to the first line break
                    val nl = out.indexOf('\n'.code.toByte())
                    skipToLineStart = nl < 0
                    val drop = if (nl >= 0) nl + 1 else out.size
                    skipped += drop
                    out = out.copyOfRange(drop, out.size)
                    if (out.isEmpty()) return Chunk(out, false, skipped)
                }
                return Chunk(out, truncated, skipped)
            }
        } catch (_: java.io.IOException) {
            return null
        }
    }

    private var skipToLineStart = false

    private companion object {
        const val DEFAULT_BACKLOG = 2L * 1024 * 1024
    }
}

/**
 * Decodes UTF-8 that arrives in arbitrary pieces: a glyph cut between two pieces is kept and
 * finished by the next one, malformed bytes become U+FFFD.
 */
class Utf8Chunker {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var carry = ByteBuffer.allocate(8 * 1024)

    /** Text for the first [n] bytes of [bytes] (plus what was left from before). */
    fun feed(bytes: ByteArray, n: Int = bytes.size): String {
        if (carry.remaining() < n) {
            val bigger = ByteBuffer.allocate(carry.position() + n + 8 * 1024)
            carry.flip()
            bigger.put(carry)
            carry = bigger
        }
        carry.put(bytes, 0, n)
        carry.flip()
        val out = CharBuffer.allocate(carry.remaining() + 1)
        decoder.decode(carry, out, false)
        carry.compact()
        out.flip()
        return out.toString()
    }

    /** Forgets a half-received glyph (the stream was cut or restarted). */
    fun reset() {
        carry.clear()
        decoder.reset()
    }
}
