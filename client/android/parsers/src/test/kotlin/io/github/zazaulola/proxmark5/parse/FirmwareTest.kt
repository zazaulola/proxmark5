package io.github.zazaulola.proxmark5.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareCatalogTest {
    private fun asset(name: String, size: Int = 100) =
        """{"name":"$name","size":$size,"browser_download_url":"https://github.com/o/r/releases/download/t/$name"}"""

    private val releasesJson = """
        [
          {"tag_name":"pm5-fw-v4.23346","name":"Proxmark5 firmware v4.23346","draft":false,"prerelease":false,
           "published_at":"2026-10-25T10:00:00Z","html_url":"https://github.com/o/r/releases/tag/pm5-fw-v4.23346",
           "body":"Built.\n\n<!-- pm5-firmware {\"source\":\"upstream\",\"repo\":\"RfidResearchGroup/proxmark3\",\"ref\":\"v4.23346\",\"commit\":\"dc0110c29d32\",\"version\":\"Iceman/HEAD/v4.23346\",\"armsrc\":\"544c2b2b1\"} -->\n",
           "assets":[${asset("pm5-bwm.fullimage.elf")},${asset("pm5.json")},${asset("pm5.fullimage.elf", 346744)},
                     ${asset("pm5.bootrom.elf")},${asset("pm5.recovery.bin")},${asset("pm5-bwm.bootrom.elf")}]},
          {"tag_name":"pm5-fw-fork-5fdad7b","name":"","draft":false,"prerelease":true,
           "published_at":"2026-10-26T10:00:00Z","html_url":"u","body":null,
           "assets":[${asset("pm5.fullimage.elf")}]},
          {"tag_name":"v1.0-app","name":"not firmware","draft":false,"prerelease":false,
           "published_at":"2026-10-27T10:00:00Z","html_url":"u","body":"","assets":[${asset("app.apk")}]},
          {"tag_name":"pm5-fw-empty","name":"no images","draft":false,"prerelease":false,
           "published_at":"2026-10-27T10:00:00Z","html_url":"u","body":"","assets":[]},
          {"tag_name":"pm5-fw-draft","name":"draft","draft":true,"prerelease":false,
           "published_at":"2026-10-27T10:00:00Z","html_url":"u","body":"","assets":[${asset("pm5.fullimage.elf")}]}
        ]
    """.trimIndent()

    @Test
    fun parsesFirmwareReleasesOnly() {
        val rel = FirmwareCatalog.parseReleases(releasesJson)
        assertEquals(listOf("pm5-fw-fork-5fdad7b", "pm5-fw-v4.23346"), rel.map { it.tag })

        val fork = rel[0]
        assertEquals("pm5-fw-fork-5fdad7b", fork.title)
        assertTrue(fork.prerelease)
        assertNull(fork.info)

        val up = rel[1]
        assertEquals(listOf("pm5", "pm5-bwm"), up.variants.map { it.name })
        val pm5 = up.variant("pm5")!!
        assertEquals(346744L, pm5.fullimage.size)
        assertEquals("pm5.bootrom.elf", pm5.bootrom?.name)
        assertEquals("pm5.recovery.bin", pm5.recovery?.name)
        assertEquals("pm5.json", pm5.manifest?.name)
        val bwm = up.variant("pm5-bwm")!!
        assertNull(bwm.recovery)
        assertNull(bwm.manifest)

        val info = up.info!!
        assertEquals("upstream", info.source)
        assertEquals("v4.23346", info.ref)
        assertEquals("544c2b2b1", info.armsrc)
        assertEquals("Iceman/HEAD/v4.23346", info.version)
    }

    @Test
    fun parsesManifest() {
        val m = FirmwareCatalog.parseManifest(
            """
            {"variant":"pm5-bwm","platform":"PM5","extras":"BWM","standalone":"","skip":"",
             "version":"Iceman/HEAD/v4.23346","armsrc":"544c2b2b1",
             "files":{"pm5-bwm.fullimage.elf":{"size":1,"sha256":"FF2C10"},"pm5-bwm.bootrom.elf":{"size":2,"sha256":"3924c2"}}}
            """.trimIndent()
        )
        assertEquals("pm5-bwm", m.variant)
        assertEquals("BWM", m.extras)
        assertEquals("ff2c10", m.sha256["pm5-bwm.fullimage.elf"])
        assertEquals("3924c2", m.sha256["pm5-bwm.bootrom.elf"])
    }

    @Test
    fun parsesSourceReleasesAndRuns() {
        val src = FirmwareCatalog.parseSourceReleases(
            """[{"tag_name":"v4.21611","name":"BREAKMEIFYOUCAN!","published_at":"2026-04-14T00:00:00Z","prerelease":false},
                {"tag_name":"v4.23346","name":"Frosty Lemon","published_at":"2026-09-18T00:00:00Z","prerelease":false}]"""
        )
        assertEquals(listOf("v4.23346", "v4.21611"), src.map { it.tag })

        val runs = FirmwareCatalog.parseRuns(
            """{"total_count":2,"workflow_runs":[
                {"id":7,"display_title":"PM5 firmware upstream v4.23346 pm5 app-abc123","status":"in_progress","conclusion":null,"html_url":"h7"},
                {"id":6,"display_title":"PM5 firmware fork master pm5 app-zzz","status":"completed","conclusion":"failure","html_url":"h6"}]}"""
        )
        assertEquals(2, runs.size)
        assertNull(runs[0].conclusion)
        assertFalse(runs[0].done)
        assertTrue(runs[1].done)
        assertFalse(runs[1].succeeded)
        assertEquals("abc", FirmwareCatalog.parseCommitSha("""{"sha":"abc","commit":{}}"""))
    }
}

class FirmwareNamingTest {
    @Test
    fun variantNamesMatchTheWorkflow() {
        // the same cases the workflow's variant_of() gives
        assertEquals("pm5", FirmwareNaming.variant(BuildOptions()))
        assertEquals("pm5-bwm", FirmwareNaming.variant(BuildOptions(extras = setOf("BWM"))))
        assertEquals(
            "pm5-bwm-no_lowbatt_beep-sa_hf_mfcsim-skip_em4x50_felica_hitag",
            FirmwareNaming.variant(
                BuildOptions(
                    extras = setOf("NO_LOWBATT_BEEP", "BWM"),
                    standalone = "HF_MFCSIM",
                    skip = setOf("HITAG", "EM4x50", "FELICA"),
                )
            ),
        )
        assertEquals("pm5-nosa", FirmwareNaming.variant(BuildOptions(standalone = FirmwareNaming.NO_STANDALONE)))
    }

    @Test
    fun releaseTags() {
        assertEquals("pm5-fw-v4.23346", FirmwareNaming.releaseTag("upstream", "v4.23346", true, ""))
        assertEquals(
            "pm5-fw-fork-5fdad7b",
            FirmwareNaming.releaseTag("fork", "master", false, "5fdad7b1096a3ad303ebf742ba1e183dd7b958ed"),
        )
    }

    @Test
    fun pm5SupportStartsWithFrostyLemon() {
        assertTrue(FirmwareNaming.supportsPm5("v4.23346"))
        assertTrue(FirmwareNaming.supportsPm5("v4.24000"))
        assertTrue(FirmwareNaming.supportsPm5("v5.100"))
        assertFalse(FirmwareNaming.supportsPm5("v4.21611"))
        assertFalse(FirmwareNaming.supportsPm5("v4.9237"))
        assertFalse(FirmwareNaming.supportsPm5("master"))
    }
}

class FlashLogTest {
    @Test
    fun followsFilesAndPercent() {
        val t = TerminalBuffer()
        t.append("[+] Loading ELF file /c/pm5.bootrom.elf\n")
        t.append("[+] \u001b[36mFlashing...\u001b[0m\n")
        assertEquals(FlashStatus(null, null), FlashLog.status(t.snapshot()))

        t.append("[+] Writing segments for file: /c/pm5.bootrom.elf\n")
        t.append("[+]  0x08000000..0x08003fff [0x4000 / 8 blocks]\n")
        assertEquals(FlashStatus("pm5.bootrom.elf", null), FlashLog.status(t.snapshot()))

        t.append("[+] Writing segments for file: /c/pm5.fullimage.elf\n")
        t.append("[+]  0x08004000..0x0805a4ff [0x564ff / 173 blocks]\n")
        t.append("\r[|]Flashing   3%")
        t.append("\r[/]Flashing  42%")
        assertEquals(FlashStatus("pm5.fullimage.elf", 42), FlashLog.status(t.snapshot()))

        t.append("\r[-]Flashing 100%\n")
        assertEquals(FlashStatus("pm5.fullimage.elf", 100), FlashLog.status(t.snapshot()))

        // a new write starts: the previous one is no longer reported
        t.append("\n[\u001b[32musb\u001b[0m] pm3 --> flash pm5.fullimage.elf\n")
        assertEquals(FlashStatus(null, null), FlashLog.status(t.snapshot()))
    }

    @Test
    fun followsDfuPhases() {
        val t = TerminalBuffer()
        t.append("[+] Writing /c/recovery.bin (362213 bytes) at 0x08000000\n")
        assertNull(FlashLog.dfuStatus(t.snapshot()))
        t.append("\r[|]Erasing   50%")
        assertEquals("Erasing" to 50, FlashLog.dfuStatus(t.snapshot()))
        t.append("\r[/]Erasing  100%\n\r[-]Writing   10%")
        assertEquals("Writing" to 10, FlashLog.dfuStatus(t.snapshot()))
    }
}
