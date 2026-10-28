package io.github.zazaulola.proxmark5.core

import io.github.zazaulola.proxmark5.parse.Library
import io.github.zazaulola.proxmark5.parse.LibraryKind
import java.io.File
import java.util.Calendar

data class LibraryFile(val file: File, val kind: LibraryKind) {
    val name: String get() = file.name
    val size: Long get() = file.length()
    val modified: Long get() = file.lastModified()
}

/**
 * What the client saved in the dump folder, sorted onto the Library's shelves by name, plus
 * the terminal logs the app saves in its own folder. Plain file access: call it off the UI thread.
 */
class LibraryRepository(private val dumpDir: File, private val logsDir: File) {

    fun list(kind: LibraryKind): List<LibraryFile> {
        val out = ArrayList<LibraryFile>()
        dumpDir.listFiles()?.forEach { f ->
            if (f.isFile && Library.classify(f.name) == kind) out += LibraryFile(f, kind)
        }
        if (kind == LibraryKind.LOGS) {
            logsDir.listFiles()?.forEach { f ->
                if (f.isFile && !f.name.startsWith(".")) out += LibraryFile(f, kind)
            }
        }
        return out.sortedByDescending { it.modified }
    }

    fun delete(file: LibraryFile): Boolean = file.file.delete()

    /** Writes [text] as a new log file and returns it. */
    fun saveLog(text: String): File {
        logsDir.mkdirs()
        val c = Calendar.getInstance()
        val name = Library.logFileName(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
        )
        return File(logsDir, name).also { it.writeText(text) }
    }
}
