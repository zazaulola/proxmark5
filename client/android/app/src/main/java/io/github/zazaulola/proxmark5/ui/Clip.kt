package io.github.zazaulola.proxmark5.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast

// ClipDescription.EXTRA_IS_SENSITIVE (API 33); keyboards and the system clipboard preview honour the same key before that
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

// A bare 6- or 16-byte hex run (a MIFARE / AES-DES key length), contiguous or space/colon separated,
// standing on its own. A line or a command copied whole may embed a key (`... -k FFFFFFFFFFFF`, a
// key-table row), and that copy must be redacted the same as a key tapped on its own.
private val KEY_IN_TEXT = Regex(
    """(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{12}|[0-9A-Fa-f]{32})(?![0-9A-Fa-f])""" +
        """|(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}[ :]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])""" +
        """|(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}[ :]){15}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])""",
)

/** Whether [text] carries a hex key, so a copy of it should be hidden from the clipboard preview. */
fun textHasKey(text: String): Boolean = KEY_IN_TEXT.containsMatchIn(text)

/**
 * Puts [text] on the clipboard. A [sensitive] clip (a key, a password) is marked so: the system
 * does not show it in its copy preview, and keyboards that suggest clipboard text leave it out. A
 * clip that embeds a key is marked the same even when the caller did not ask, so copying a whole
 * line or command never leaks a key into the paste preview. Android 13+ confirms a copy by itself,
 * so the app only says something on older versions.
 */
fun copyText(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val secret = sensitive || textHasKey(text)
    val clip = ClipData.newPlainText(label, text)
    if (secret) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    try {
        cm.setPrimaryClip(clip)
    } catch (e: RuntimeException) {
        // a clip too big for binder, or a clipboard that refuses (work profiles): say so instead of crashing
        Toast.makeText(context, "Не удалось скопировать", Toast.LENGTH_SHORT).show()
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        val shown = if (secret) label else text.take(40)
        Toast.makeText(context, "Скопировано: $shown", Toast.LENGTH_SHORT).show()
    }
}
