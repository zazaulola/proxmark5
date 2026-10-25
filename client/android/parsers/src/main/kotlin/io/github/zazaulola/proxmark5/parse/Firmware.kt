package io.github.zazaulola.proxmark5.parse

import org.json.JSONArray
import org.json.JSONObject

/** A downloadable file of a GitHub release. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

/** One build of a firmware release: its files share the "<name>." prefix. */
data class FirmwareVariant(
    val name: String,
    val fullimage: ReleaseAsset,
    val bootrom: ReleaseAsset? = null,
    val recovery: ReleaseAsset? = null,
    val manifest: ReleaseAsset? = null,
)

/** Where a firmware release comes from: the "<!-- pm5-firmware {...} -->" line of its notes. */
data class FirmwareInfo(
    /** "upstream" or "fork" */
    val source: String,
    val repo: String,
    val ref: String,
    val commit: String,
    /** git version string the firmware reports, e.g. "Iceman/HEAD/v4.23346" */
    val version: String,
    /** hash of the ARM sources, equal to the client's when both come from the same tree */
    val armsrc: String,
)

/** A pm5-fw-* release, built by .github/workflows/pm5-firmware-release.yml. */
data class FirmwareRelease(
    val tag: String,
    val title: String,
    val publishedAt: String,
    val prerelease: Boolean,
    val htmlUrl: String,
    val info: FirmwareInfo?,
    val variants: List<FirmwareVariant>,
) {
    fun variant(name: String) = variants.firstOrNull { it.name == name }
}

/** A release of a source repository: a version firmware can be built from. */
data class SourceRelease(val tag: String, val name: String, val publishedAt: String, val prerelease: Boolean)

/** <variant>.json next to the images: build options and the SHA-256 of every file. */
data class VariantManifest(
    val variant: String,
    val version: String,
    val armsrc: String,
    val extras: String,
    val standalone: String,
    val skip: String,
    val sha256: Map<String, String>,
)

/** A run of the firmware workflow. */
data class WorkflowRun(
    val id: Long,
    val title: String,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
) {
    val done get() = status == "completed"
    val succeeded get() = done && conclusion == "success"
}

/** Reads the GitHub REST answers the firmware screen uses. */
object FirmwareCatalog {
    private val INFO = Regex("""<!--\s*pm5-firmware\s+(\{.*?\})\s*-->""", RegexOption.DOT_MATCHES_ALL)

    /** Firmware releases from `GET /repos/{repo}/releases`, newest first. */
    fun parseReleases(json: String): List<FirmwareRelease> {
        val arr = JSONArray(json)
        val out = ArrayList<FirmwareRelease>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val tag = r.str("tag_name") ?: continue
            if (!tag.startsWith(FirmwareNaming.TAG_PREFIX) || r.optBoolean("draft")) continue
            val variants = variants(assets(r.optJSONArray("assets")))
            if (variants.isEmpty()) continue
            out += FirmwareRelease(
                tag = tag,
                title = r.str("name")?.takeIf { it.isNotBlank() } ?: tag,
                publishedAt = r.str("published_at") ?: "",
                prerelease = r.optBoolean("prerelease"),
                htmlUrl = r.str("html_url") ?: "",
                info = parseInfo(r.str("body")),
                variants = variants,
            )
        }
        return out.sortedByDescending { it.publishedAt }
    }

    /** Groups release files into variants: every "<v>.fullimage.elf" makes one. */
    fun variants(assets: List<ReleaseAsset>): List<FirmwareVariant> {
        val byName = assets.associateBy { it.name }
        return assets.filter { it.name.endsWith(FULLIMAGE) }
            .map { fi ->
                val v = fi.name.removeSuffix(FULLIMAGE)
                FirmwareVariant(v, fi, byName["$v.bootrom.elf"], byName["$v.recovery.bin"], byName["$v.json"])
            }
            // the plain "pm5" first, then by name
            .sortedWith(compareBy({ it.name.length }, { it.name }))
    }

    fun parseInfo(body: String?): FirmwareInfo? {
        val m = INFO.find(body ?: return null) ?: return null
        return runCatching {
            val o = JSONObject(m.groupValues[1])
            FirmwareInfo(
                source = o.str("source") ?: "",
                repo = o.str("repo") ?: "",
                ref = o.str("ref") ?: "",
                commit = o.str("commit") ?: "",
                version = o.str("version") ?: "",
                armsrc = o.str("armsrc") ?: "",
            )
        }.getOrNull()
    }

    /** Releases of a source repository (`GET /repos/{repo}/releases`), newest first. */
    fun parseSourceReleases(json: String): List<SourceRelease> {
        val arr = JSONArray(json)
        val out = ArrayList<SourceRelease>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val tag = r.str("tag_name") ?: continue
            out += SourceRelease(tag, r.str("name") ?: tag, r.str("published_at") ?: "", r.optBoolean("prerelease"))
        }
        return out.sortedByDescending { it.publishedAt }
    }

    fun parseManifest(json: String): VariantManifest {
        val o = JSONObject(json)
        val files = o.optJSONObject("files")
        val sha = HashMap<String, String>()
        if (files != null) {
            val keys = files.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                files.optJSONObject(k)?.str("sha256")?.let { sha[k] = it.lowercase() }
            }
        }
        return VariantManifest(
            variant = o.str("variant") ?: "",
            version = o.str("version") ?: "",
            armsrc = o.str("armsrc") ?: "",
            extras = o.str("extras") ?: "",
            standalone = o.str("standalone") ?: "",
            skip = o.str("skip") ?: "",
            sha256 = sha,
        )
    }

    /** `GET /repos/{repo}/commits/{ref}` -> the full commit hash. */
    fun parseCommitSha(json: String): String = JSONObject(json).getString("sha")

    /** `GET /repos/{repo}/actions/workflows/{file}/runs`. */
    fun parseRuns(json: String): List<WorkflowRun> {
        val arr = JSONObject(json).optJSONArray("workflow_runs") ?: return emptyList()
        val out = ArrayList<WorkflowRun>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            out += WorkflowRun(
                id = r.optLong("id"),
                title = r.str("display_title") ?: r.str("name") ?: "",
                status = r.str("status") ?: "",
                conclusion = r.str("conclusion"),
                htmlUrl = r.str("html_url") ?: "",
            )
        }
        return out
    }

    private fun assets(arr: JSONArray?): List<ReleaseAsset> {
        if (arr == null) return emptyList()
        val out = ArrayList<ReleaseAsset>()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val name = a.str("name") ?: continue
            val url = a.str("browser_download_url") ?: continue
            out += ReleaseAsset(name, url, a.optLong("size"))
        }
        return out
    }

    private const val FULLIMAGE = ".fullimage.elf"
}

/**
 * The string at [key], or null when it is missing or JSON null. Android's org.json
 * turns null into "null" in optString(), so don't rely on that.
 */
private fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)

/** Options of a firmware build: the inputs of the firmware workflow. */
data class BuildOptions(
    /** PLATFORM_EXTRAS tokens, see [FirmwareNaming.PM5_EXTRAS] */
    val extras: Set<String> = emptySet(),
    /** "" for the default standalone mode, [FirmwareNaming.NO_STANDALONE], or a mode name */
    val standalone: String = "",
    /** SKIP_<module> tokens without the prefix, see [FirmwareNaming.SKIPPABLE] */
    val skip: Set<String> = emptySet(),
)

/** Names shared with .github/workflows/pm5-firmware-release.yml: keep both in step. */
object FirmwareNaming {
    const val TAG_PREFIX = "pm5-fw-"
    const val NO_STANDALONE = "none"
    const val DEFAULT_STANDALONE = "LF_SAMYRUN"

    /** The first upstream release (v4.23346, Frosty Lemon) that builds for PLATFORM=PM5. */
    const val FIRST_PM5_RELEASE = 23346

    val PM5_EXTRAS = listOf("BWM", "NO_LOWBATT_BEEP", "NO_LOWBATT_SHUTDOWN")

    /** Firmware parts a build can leave out (common_arm/Makefile.hal SKIP_* switches). */
    val SKIPPABLE = listOf(
        "LF", "HITAG", "EM4x50", "EM4x70", "ZX8211", "HF", "ISO15693", "LEGICRF",
        "ISO14443a", "ISO14443b", "ICLASS", "FELICA", "NFCBARCODE", "HFSNIFF", "HFPLOT", "CEP",
    )

    /** pm5[-extra...][-sa_mode|-nosa][-skip_a_b...], lowercase, extras and modules sorted. */
    fun variant(o: BuildOptions): String {
        val sb = StringBuilder("pm5")
        o.extras.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSortedSet()
            .forEach { sb.append('-').append(it) }
        val sa = o.standalone.trim()
        when {
            sa.isEmpty() -> {}
            sa == NO_STANDALONE || sa == NO_STANDALONE.uppercase() -> sb.append("-nosa")
            else -> sb.append("-sa_").append(sa.lowercase())
        }
        val skip = o.skip.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSortedSet()
        if (skip.isNotEmpty()) sb.append("-skip_").append(skip.joinToString("_"))
        return sb.toString()
    }

    /** The release a build lands in: per tag, or per commit for a branch or a commit. */
    fun releaseTag(source: String, ref: String, isTag: Boolean, commit: String): String =
        if (isTag) TAG_PREFIX + ref.replace('/', '-') else "$TAG_PREFIX$source-${commit.take(7)}"

    /** True for upstream tags from [FIRST_PM5_RELEASE] on: older ones can't build a PM5 image. */
    fun supportsPm5(tag: String): Boolean {
        val m = Regex("""^v(\d+)\.(\d+)""").find(tag) ?: return false
        val major = m.groupValues[1].toInt()
        return major > 4 || (major == 4 && m.groupValues[2].toInt() >= FIRST_PM5_RELEASE)
    }
}
