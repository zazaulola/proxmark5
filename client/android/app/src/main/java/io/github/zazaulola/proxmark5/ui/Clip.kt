package io.github.zazaulola.proxmark5.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast

// ClipDescription.EXTRA_IS_SENSITIVE (API 33); keyboards and the system clipboard preview honour the same key before that
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/**
 * Puts [text] on the clipboard. A [sensitive] clip (a key, a password) is marked so: the system
 * does not show it in its copy preview, and keyboards that suggest clipboard text leave it out.
 * Android 13+ confirms a copy by itself, so the app only says something on older versions.
 */
fun copyText(context: Context, label: String, text: String, sensitive: Boolean = false) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    try {
        cm.setPrimaryClip(clip)
    } catch (e: RuntimeException) {
        // a clip too big for binder, or a clipboard that refuses (work profiles): say so instead of crashing
        Toast.makeText(context, "Не удалось скопировать", Toast.LENGTH_SHORT).show()
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        val shown = if (sensitive) label else text.take(40)
        Toast.makeText(context, "Скопировано: $shown", Toast.LENGTH_SHORT).show()
    }
}
