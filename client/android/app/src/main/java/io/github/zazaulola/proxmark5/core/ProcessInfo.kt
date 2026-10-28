package io.github.zazaulola.proxmark5.core

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/** Which of the app's processes the code runs in: the UI one or the service one (":pm3"). */
object ProcessInfo {
    private const val SERVICE_SUFFIX = ":pm3"

    /** Name of this process, e.g. "io.github.zazaulola.proxmark5" or "io.github.zazaulola.proxmark5:pm3". */
    val name: String by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault("")
        }
    }

    /** True in the service process, where the client library lives. */
    val isService: Boolean get() = name.endsWith(SERVICE_SUFFIX)

    /** True in the process with the UI. Anything unknown counts as the UI. */
    val isUi: Boolean get() = !isService

    /** The name the service process has in [ctx]'s package. */
    fun servicePackageProcess(ctx: Context) = ctx.packageName + SERVICE_SUFFIX
}
