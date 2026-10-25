package io.github.zazaulola.proxmark5.firmware

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.zazaulola.proxmark5.parse.BuildOptions
import io.github.zazaulola.proxmark5.parse.FirmwareCatalog
import io.github.zazaulola.proxmark5.parse.FirmwareNaming
import io.github.zazaulola.proxmark5.parse.FirmwareRelease
import io.github.zazaulola.proxmark5.parse.FirmwareVariant
import io.github.zazaulola.proxmark5.parse.ReleaseAsset
import io.github.zazaulola.proxmark5.parse.SourceRelease
import io.github.zazaulola.proxmark5.parse.WorkflowRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** Firmware bundled in the APK (assets/firmware), built from the same tree as the client. */
data class BundledFirmware(val version: String, val armsrc: String, val hasBootrom: Boolean, val hasRecovery: Boolean)

/** What to flash. */
sealed interface FirmwareChoice {
    val title: String

    /** ARM source hash, when known: equal to the client's means the firmware matches the app. */
    val armsrc: String?

    data class Bundled(val fw: BundledFirmware) : FirmwareChoice {
        override val title get() = "Встроенная прошивка"
        override val armsrc get() = fw.armsrc
    }

    data class Release(val release: FirmwareRelease, val variant: FirmwareVariant) : FirmwareChoice {
        override val title get() = "${release.title} · ${variant.name}"
        override val armsrc get() = release.info?.armsrc
    }

    /** Files picked on the phone: ELF images for the flasher, a .bin for DFU. */
    data class Local(val uris: List<Uri>) : FirmwareChoice {
        override val title get() = "Файл с телефона"
        override val armsrc: String? get() = null
    }
}

enum class FirmwareSource(val key: String, val label: String) {
    Upstream("upstream", "Апстрим (RRG)"),
    Fork("fork", "Форк"),
}

/** A firmware build to ask GitHub Actions for. */
data class BuildRequest(
    val source: FirmwareSource,
    /** a tag, a branch or a commit of the source repository */
    val ref: String,
    val isTag: Boolean,
    val options: BuildOptions,
)

sealed interface BuildStatus {
    data class Running(val tag: String, val variant: String, val note: String, val run: WorkflowRun? = null) : BuildStatus
    data class Ready(val tag: String, val variant: String, val alreadyBuilt: Boolean) : BuildStatus
    data class Failed(val message: String, val url: String? = null) : BuildStatus
}

data class FirmwareUi(
    val loading: Boolean = false,
    val error: String? = null,
    /** firmware releases of [FirmwareSettings.repo] */
    val releases: List<FirmwareRelease> = emptyList(),
    /** upstream releases a PM5 firmware can be built from */
    val upstream: List<SourceRelease> = emptyList(),
    val build: BuildStatus? = null,
)

data class FirmwareSettings(
    /** repository with the firmware workflow and its releases */
    val repo: String = DEFAULT_REPO,
    /** branch the workflow is run from */
    val branch: String = "master",
    val hasToken: Boolean = false,
) {
    companion object {
        const val DEFAULT_REPO = "zazaulola/proxmark5"
    }
}

/**
 * Firmware catalog, builds and downloads. Firmware is not built on the phone: the
 * "Proxmark5 firmware release" workflow builds it on GitHub Actions and publishes it as
 * a release of [FirmwareSettings.repo]; this lists those releases, starts new builds
 * (with a token) and downloads the chosen images. Flashing is the view model's job.
 */
class FirmwareController(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("firmware", Context.MODE_PRIVATE)

    // kept out of backups: noBackupFilesDir is never copied off the phone
    private val tokenFile = File(app.noBackupFilesDir, "github-token")
    private val api = GitHubApi { readToken() }
    private val cache = File(app.cacheDir, "firmware")

    private val _ui = MutableStateFlow(FirmwareUi())
    val ui: StateFlow<FirmwareUi> = _ui.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<FirmwareSettings> = _settings.asStateFlow()

    private var buildJob: Job? = null

    val bundled: BundledFirmware? by lazy { loadBundled() }

    fun refresh() = scope.launch { load() }

    private suspend fun load() {
        _ui.update { it.copy(loading = true, error = null) }
        try {
            val repo = _settings.value.repo
            val releases = FirmwareCatalog.parseReleases(api.get("repos/$repo/releases?per_page=100"))
            val upstream = runCatching {
                FirmwareCatalog.parseSourceReleases(api.get("repos/$UPSTREAM/releases?per_page=30"))
                    .filter { FirmwareNaming.supportsPm5(it.tag) }
            }.getOrDefault(_ui.value.upstream)
            _ui.update { it.copy(loading = false, releases = releases, upstream = upstream) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    fun saveSettings(repo: String, branch: String, token: String?) {
        val r = repo.trim().ifEmpty { FirmwareSettings.DEFAULT_REPO }
        val b = branch.trim().ifEmpty { "master" }
        prefs.edit().putString("repo", r).putString("branch", b).apply()
        if (token != null) {
            if (token.isBlank()) tokenFile.delete() else tokenFile.writeText(token.trim())
        }
        _settings.value = loadSettings()
        refresh()
    }

    private fun loadSettings() = FirmwareSettings(
        repo = prefs.getString("repo", null) ?: FirmwareSettings.DEFAULT_REPO,
        branch = prefs.getString("branch", null) ?: "master",
        hasToken = !readToken().isNullOrBlank(),
    )

    private fun readToken(): String? = runCatching { tokenFile.readText().trim() }.getOrNull()

    fun clearBuild() {
        buildJob?.cancel()
        _ui.update { it.copy(build = null) }
    }

    /** Asks GitHub Actions for [req], unless that build is already published, and follows it. */
    fun startBuild(req: BuildRequest) {
        if (buildJob?.isActive == true) return
        buildJob = scope.launch {
            try {
                build(req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(build = BuildStatus.Failed(e.message ?: e.toString())) }
            }
        }
    }

    private suspend fun build(req: BuildRequest) {
        val s = _settings.value
        val variant = FirmwareNaming.variant(req.options)
        val srcRepo = if (req.source == FirmwareSource.Fork) s.repo else UPSTREAM
        _ui.update { it.copy(build = BuildStatus.Running("", variant, "Проверяю версию…")) }

        // a branch moves: build its current commit, so the release name is known up front
        val ref = if (req.isTag) req.ref else
            FirmwareCatalog.parseCommitSha(api.get("repos/$srcRepo/commits/${enc(req.ref)}"))
        val tag = FirmwareNaming.releaseTag(req.source.key, ref, req.isTag, ref)

        load()
        if (_ui.value.releases.firstOrNull { it.tag == tag }?.variant(variant) != null) {
            _ui.update { it.copy(build = BuildStatus.Ready(tag, variant, alreadyBuilt = true)) }
            return
        }
        if (!s.hasToken) {
            throw IOException("Для сборки нужен GitHub-токен с правом Actions: write на ${s.repo}")
        }

        val requestId = "app-" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val inputs = JSONObject()
            .put("source", req.source.key)
            .put("ref", ref)
            .put("standalone", req.options.standalone)
            .put("extras", req.options.extras.sorted().joinToString(" "))
            .put("skip", req.options.skip.sorted().joinToString(" "))
            .put("variant", variant)
            .put("request_id", requestId)
        try {
            api.post(
                "repos/${s.repo}/actions/workflows/$WORKFLOW/dispatches",
                JSONObject().put("ref", s.branch).put("inputs", inputs),
            )
        } catch (e: GitHubException) {
            if (e.code == 404 || e.code == 422) {
                throw IOException(
                    "${e.message}. Workflow $WORKFLOW должен быть в ветке по умолчанию ${s.repo} " +
                        "и в ветке «${s.branch}», а токен — иметь доступ к Actions"
                )
            }
            throw e
        }

        var run: WorkflowRun? = null
        val started = System.currentTimeMillis()
        _ui.update { it.copy(build = BuildStatus.Running(tag, variant, "Сборка запущена, жду начала…")) }
        while (System.currentTimeMillis() - started < BUILD_TIMEOUT_MS) {
            delay(POLL_MS)
            run = runCatching {
                FirmwareCatalog.parseRuns(
                    api.get("repos/${s.repo}/actions/workflows/$WORKFLOW/runs?event=workflow_dispatch&per_page=20")
                ).firstOrNull { it.title.contains(requestId) }
            }.getOrNull() ?: run
            val minutes = (System.currentTimeMillis() - started) / 60_000
            val note = when (run?.status) {
                null, "queued", "requested", "waiting", "pending" -> "В очереди GitHub Actions… ($minutes мин)"
                "completed" -> "Сборка закончилась"
                else -> "Собираю… ($minutes мин, обычно 5–10)"
            }
            _ui.update { it.copy(build = BuildStatus.Running(tag, variant, note, run)) }

            val r = run ?: continue
            if (!r.done) continue
            if (!r.succeeded) {
                _ui.update { it.copy(build = BuildStatus.Failed("Сборка не удалась (${r.conclusion})", r.htmlUrl)) }
                return
            }
            load()
            val found = _ui.value.releases.firstOrNull { it.tag == tag }?.variant(variant) != null
            _ui.update {
                it.copy(
                    build = if (found) BuildStatus.Ready(tag, variant, alreadyBuilt = false)
                    else BuildStatus.Failed("Сборка прошла, но релиз $tag не найден", r.htmlUrl)
                )
            }
            return
        }
        _ui.update { it.copy(build = BuildStatus.Failed("Сборка не закончилась за 45 минут", run?.htmlUrl)) }
    }

    fun findChoice(tag: String, variant: String): FirmwareChoice.Release? {
        val rel = _ui.value.releases.firstOrNull { it.tag == tag } ?: return null
        return FirmwareChoice.Release(rel, rel.variant(variant) ?: return null)
    }

    /**
     * Gets the ELF images of [choice] into the cache, in flashing order (bootloader first,
     * like pm3-flash-all). Release files are checked against their SHA-256 manifest.
     */
    suspend fun fetchImages(
        choice: FirmwareChoice,
        includeBootrom: Boolean,
        progress: (String, Int?) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        when (choice) {
            is FirmwareChoice.Bundled -> buildList {
                if (includeBootrom) add(copyAsset("bootrom.elf"))
                add(copyAsset("fullimage.elf"))
            }

            is FirmwareChoice.Release -> {
                val v = choice.variant
                val assets = buildList {
                    if (includeBootrom) add(v.bootrom ?: throw IOException("В сборке ${v.name} нет bootrom.elf"))
                    add(v.fullimage)
                }
                downloadVerified(choice.release, v, assets, progress)
            }

            is FirmwareChoice.Local -> choice.uris.map { copyUri(it, "local") }.onEach {
                if (!isElf(it)) throw IOException("${it.name}: не ELF-файл. Для прошивки нужны *.elf (fullimage/bootrom)")
            }
        }
    }

    /** The raw image for the DFU loader (recovery.bin: bootloader and firmware in one). */
    suspend fun fetchRecovery(choice: FirmwareChoice, progress: (String, Int?) -> Unit): File =
        withContext(Dispatchers.IO) {
            when (choice) {
                is FirmwareChoice.Bundled -> copyAsset("recovery.bin")
                is FirmwareChoice.Release -> {
                    val r = choice.variant.recovery ?: throw IOException("В сборке ${choice.variant.name} нет recovery.bin")
                    downloadVerified(choice.release, choice.variant, listOf(r), progress).single()
                }

                is FirmwareChoice.Local -> {
                    val f = copyUri(choice.uris.single(), "local")
                    if (isElf(f)) throw IOException("${f.name}: это ELF, для DFU нужен сырой образ recovery.bin")
                    f
                }
            }
        }

    private suspend fun downloadVerified(
        release: FirmwareRelease,
        variant: FirmwareVariant,
        assets: List<ReleaseAsset>,
        progress: (String, Int?) -> Unit,
    ): List<File> {
        val dir = File(cache, release.tag).apply { mkdirs() }
        val sha = variant.manifest?.let { m ->
            progress("Скачиваю ${m.name}…", null)
            val f = File(dir, m.name)
            api.download(m.url, f) { _, _ -> }
            FirmwareCatalog.parseManifest(f.readText()).sha256
        } ?: emptyMap()
        return assets.map { a ->
            val f = File(dir, a.name)
            val got = api.download(a.url, f) { done, total ->
                progress("Скачиваю ${a.name}…", if (total > 0) (done * 100 / total).toInt() else null)
            }
            val want = sha[a.name]
            if (want != null && want != got) {
                f.delete()
                throw IOException("${a.name}: контрольная сумма не совпала, файл повреждён")
            }
            f
        }
    }

    private fun copyAsset(name: String): File {
        val dest = File(cache, "bundled/$name").apply { parentFile?.mkdirs() }
        app.assets.open("firmware/$name").use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    private fun copyUri(uri: Uri, dir: String): File {
        val name = displayName(uri)?.replace('/', '_') ?: "image.bin"
        val dest = File(cache, "$dir/$name").apply { parentFile?.mkdirs() }
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть $name")
        input.use { i -> dest.outputStream().use { i.copyTo(it) } }
        return dest
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun isElf(f: File): Boolean {
        val head = ByteArray(4)
        val n = f.inputStream().use { it.read(head) }
        return n == 4 && head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
            head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
    }

    private fun loadBundled(): BundledFirmware? = runCatching {
        val files = app.assets.list("firmware")?.toSet() ?: return null
        if ("fullimage.elf" !in files) return null
        val info = runCatching {
            JSONObject(app.assets.open("firmware/firmware.json").bufferedReader().use { it.readText() })
        }.getOrNull()
        BundledFirmware(
            version = info?.optString("version").orEmpty(),
            armsrc = info?.optString("armsrc").orEmpty(),
            hasBootrom = "bootrom.elf" in files,
            hasRecovery = "recovery.bin" in files,
        )
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val UPSTREAM = "RfidResearchGroup/proxmark3"
        const val WORKFLOW = "pm5-firmware-release.yml"
        private const val POLL_MS = 15_000L
        private const val BUILD_TIMEOUT_MS = 45 * 60_000L
    }
}
