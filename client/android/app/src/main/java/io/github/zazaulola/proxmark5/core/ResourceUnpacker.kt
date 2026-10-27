package io.github.zazaulola.proxmark5.core

import android.content.Context
import java.io.File

/**
 * Unpacks the client resource tree bundled in assets/pm3 into $HOME/.proxmark3,
 * which is the first place the client searches (fileutils.c). Re-runs only when the
 * app version changes, so the one-time copy does not slow every launch.
 */
object ResourceUnpacker {
    private const val ASSET_ROOT = "pm3"
    private const val STAMP = ".asset-version"

    fun unpack(home: File, context: Context? = ClientApp.instance) {
        val ctx = context ?: return
        val dest = File(home, ".proxmark3").apply { mkdirs() }
        val stamp = File(dest, STAMP)
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                @Suppress("DEPRECATION") (it.longVersionCode.toString() + ":" + it.versionName)
            }
        }.getOrDefault("dev")

        if (stamp.exists() && stamp.readText() == version) return
        copyDir(ctx, ASSET_ROOT, dest)
        stamp.writeText(version)
    }

    private fun copyDir(ctx: Context, assetPath: String, dest: File) {
        val entries = ctx.assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            // a file: assets.list() returns empty for leaves
            copyFile(ctx, assetPath, dest)
            return
        }
        dest.mkdirs()
        for (name in entries) {
            val child = "$assetPath/$name"
            val grand = ctx.assets.list(child)
            if (grand.isNullOrEmpty()) {
                copyFile(ctx, child, File(dest, name))
            } else {
                copyDir(ctx, child, File(dest, name))
            }
        }
    }

    private fun copyFile(ctx: Context, assetPath: String, dest: File) {
        runCatching {
            ctx.assets.open(assetPath).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
