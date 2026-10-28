package io.github.zazaulola.proxmark5.core

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Unpacks the client resource tree bundled in assets/pm3 into $HOME/.proxmark3,
 * which is the first place the client searches (fileutils.c). Re-runs only when the
 * app is installed again, so the one-time copy does not slow every launch.
 *
 * The client calls exit() when a table it reads (hardnested) is cut short or damaged, so a
 * copy that failed half way must not pass for a finished one: every file is written under a
 * temporary name and renamed when complete, the version stamp is written only after everything
 * was copied, and any failure is thrown to the caller (the service reports it, it does not die of it).
 *
 * Only the service process calls this (ClientEngine.bringUp, on the one client thread). The
 * temporary names are the same for every caller, so a second process unpacking at the same time
 * would write into the same files: the UI process must never call it.
 */
object ResourceUnpacker {
    private const val ASSET_ROOT = "pm3"
    private const val STAMP = ".asset-version"
    private const val PART = ".part"

    /** Copies the resources into [home] unless this app version did it already. Throws [IOException] if it can not. */
    fun unpack(ctx: Context, home: File) {
        val dest = File(home, ".proxmark3")
        if (!dest.isDirectory && !dest.mkdirs()) throw IOException("не удалось создать ${dest.path}")
        val stamp = File(dest, STAMP)
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        // lastUpdateTime moves with every install, so an APK rebuilt under the same version number
        // (a CI build, `adb install -r`) is unpacked again, and so is a tree restored from a backup
        val version = PackageInfoCompat.getLongVersionCode(info).toString() + ":" + info.versionName +
            ":" + info.lastUpdateTime

        if (runCatching { stamp.readText() }.getOrNull() == version) return
        // an unpack that is interrupted must not look finished next time
        stamp.delete()
        copyTree(ctx, ASSET_ROOT, dest)
        stamp.writeText(version)
    }

    private fun copyTree(ctx: Context, assetPath: String, dest: File) {
        val children = ctx.assets.list(assetPath) ?: throw IOException("не удалось прочитать ресурсы $assetPath")
        if (children.isEmpty()) {
            // an APK built without the client resources has no assets/pm3, and listing a path that is
            // not there gives an empty array too: say so, not "unpacked" with nothing under a stamp.
            // Only the top level can say it, an empty directory further down is legitimate
            if (assetPath == ASSET_ROOT) throw IOException("в APK нет ресурсов клиента")
            // assets.list() returns nothing for a file
            copyFile(ctx, assetPath, dest)
            return
        }
        if (!dest.isDirectory && !dest.mkdirs()) throw IOException("не удалось создать ${dest.path}")
        for (name in children) copyTree(ctx, "$assetPath/$name", File(dest, name))
    }

    private fun copyFile(ctx: Context, assetPath: String, dest: File) {
        val input = try {
            ctx.assets.open(assetPath)
        } catch (e: FileNotFoundException) {
            // listed with nothing in it, and not a file: an empty directory
            return
        }
        val part = File(dest.parentFile, dest.name + PART)
        try {
            input.use { src -> part.outputStream().use { src.copyTo(it) } }
        } catch (e: IOException) {
            part.delete()
            throw IOException("не удалось распаковать $assetPath: ${e.message}", e)
        }
        if (!part.renameTo(dest)) {
            part.delete()
            throw IOException("не удалось записать ${dest.path}")
        }
    }
}
