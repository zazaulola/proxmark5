package io.github.zazaulola.proxmark5.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

private val TEXT_EXTENSIONS = setOf("log", "txt", "json", "eml", "dic", "keys")

/**
 * Offers [file] to other apps (mail, messengers, a file manager). The app's own folders are
 * not reachable from outside on recent Android, so the file goes out through a FileProvider.
 */
fun shareFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension.lowercase() in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, file.name)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения, которое может принять файл", Toast.LENGTH_SHORT).show()
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, "Не удалось открыть файл для отправки", Toast.LENGTH_SHORT).show()
    }
}
