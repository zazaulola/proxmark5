package io.github.zazaulola.proxmark5.parse.params

import io.github.zazaulola.proxmark5.parse.CommandHelp
import io.github.zazaulola.proxmark5.parse.HelpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParamsTest {
    // sections copied from `proxmark3 --fulltext`, picked for their quirks
    private val dump: Map<String, CommandHelp> = HelpText.parseDump(
        checkNotNull(javaClass.getResource("/help/params-dump.txt")) { "fixture missing" }.readText()
    )

    private fun specs(cmd: String) = ParamSpecs.of(cmd, assertNotNull(dump[cmd], "no section for $cmd"))
    private fun spec(cmd: String, key: String) = specs(cmd).first { it.key == key || it.short == key }

    // ---- reading the help ----

    @Test
    fun aliasListIsOneOptionWithAValue() {
        val k = spec("hf vas reader", "-k")
        assertEquals("--key", k.long)
        assertTrue("--rpk" in k.aliases && "--file" in k.aliases)
        assertEquals("pem|der-b64|der-hex|scalar-b64|scalar-hex|path", k.placeholder)
        assertTrue(k.repeatable)
        assertIs<ValueType.Text>(k.type)
        assertTrue(spec("hf vas reader", "--pid").repeatable)
        assertFalse(spec("hf vas reader", "--url").repeatable)
    }

    @Test
    fun repeatablePositionals() {
        val raw = spec("hf 14a raw", "<hex>")
        assertTrue(raw.positional && raw.required && raw.repeatable)
        assertIs<ValueType.Hex>(raw.type)
        val genac = spec("emv genac", "<hex>")
        assertTrue(genac.required && genac.repeatable)
        val params = spec("data rawdemod", "<params>")
        assertTrue(params.repeatable)
        assertFalse(params.required)
        assertTrue(spec("hf mf chk", "-k").repeatable)
    }

    @Test
    fun plusIsPartOfALongName() {
        val keys = specs("hf mf cload").map { it.key }
        assertTrue("--1k+" in keys && "--1k" in keys)
        assertEquals(keys.size, keys.toSet().size, "keys repeat: $keys")
        val d = CommandLine.parse("hf mf cload", "hf mf cload --1k+", specs("hf mf cload"))
        assertEquals("--1k+", d.tags.single().spec?.key)
        assertEquals("hf mf cload --1k+", CommandLine.build(d))
    }

    @Test
    fun bracketedValueListIsAChoice() {
        val smode = spec("hf sniff", "--smode")
        assertEquals(ValueType.Choice(listOf("none", "drop", "min", "max", "avg")), smode.type)
    }

    @Test
    fun keyRangeIsExpanded() {
        val t = assertIs<ValueType.Choice>(spec("hf mfdes createfile", "--rrights").type)
        assertEquals(16, t.values.size)
        assertEquals("key0", t.values.first())
        assertEquals(listOf("key13", "free", "deny"), t.values.takeLast(3))
    }

    @Test
    fun shortAliasesAndUsageOnlyValues() {
        // -s, -S, --select
        val sel = spec("piv select", "--select")
        assertEquals("-s", sel.short)
        assertEquals(listOf("-S"), sel.aliases)
        assertEquals(ValueType.Flag, sel.type)
        // `-u hex`: the placeholder has no <> and only the usage line shows it is a value
        val uid = spec("hf 14b sim", "--uid")
        assertEquals("hex", uid.placeholder)
        assertIs<ValueType.Hex>(uid.type)
        assertEquals("4byte UID/PUPI", uid.description)
        // -k has no options row at all, only `[-k Replace default key for NDEF]` in the usage
        val k = spec("hf mfu ndefread", "-k")
        assertFalse(k.isFlag)
        // `-y[h]`: the flag is -y, and it is required
        val yes = spec("hf mf gen3freeze", "--yes")
        assertTrue(yes.required && yes.isFlag)
        assertTrue(specs("hf mf gen3freeze").none { it.key.contains('[') })
        // `-b <num>[,<num>[,...]]` is a list, not a number
        assertIs<ValueType.Text>(spec("hf mfdes createmfcmapping", "-b").type)
        assertEquals(ValueType.Choice(listOf("0", "1", "2")), spec("hf mfu countertear", "--cnt").type)
    }

    @Test
    fun requiredFlagsAreNotPutInForTheUser() {
        // a required flag confirms something that can't be undone: the user adds it on purpose
        val s = specs("hf mf gen3freeze")
        val d = CommandDraft.start("hf mf gen3freeze", s)
        assertTrue(d.tags.isEmpty())
        assertTrue(DraftCheck.problems(d, s).any { "--yes" in it.problem.message })
        assertTrue(DraftCheck.problems(d.add(spec("hf mf gen3freeze", "--yes")), s).isEmpty())
    }

    @Test
    fun commentsAfterExamplesAreNotParameters() {
        val ex = assertNotNull(dump["lf em 4x70 setkey"]).examples.first { it.text.contains("F32AA98CF5BE4ADFA6D3480B") }
        assertEquals("lf em 4x70 setkey -k F32AA98CF5BE4ADFA6D3480B", ex.text)
        assertEquals("pm3 test key", ex.note)
        val bt = assertNotNull(dump["hf iclass blacktears"]).examples.first { "<--" in it.text || it.note?.contains("debit") == true }
        assertFalse("<--" in bt.text)
    }

    // ---- types ----

    @Test
    fun typesFromPlaceholders() {
        assertEquals(ValueType.Number(), spec("hf mf rdbl", "--blk").type)
        assertIs<ValueType.Hex>(spec("hf mf rdbl", "-k").type)
        assertEquals(ValueType.Flag, spec("hf mf rdbl", "-a").type)
        assertEquals(ValueType.Number(), spec("wiegand encode", "--fc").type)
        assertEquals(ValueType.Bits, spec("data num", "--bin").type)
        assertEquals(ValueType.Text(hint = "hex:sectors"), spec("mad encode", "--aid").type)
        val algo = assertIs<ValueType.Choice>(spec("hf mfdes auth", "--algo").type)
        assertEquals(listOf("DES", "2TDEA", "3TDEA", "AES"), algo.values)
        val id = assertIs<ValueType.Hex>(spec("hf fmcos createdir", "--id").type)
        assertEquals(ByteLengths(listOf(2..2)), id.exact)
    }

    @Test
    fun fileRoles() {
        assertEquals(ValueType.File(FileRole.LOAD), spec("hf mf eload", "--file").type)
        assertEquals(ValueType.File(FileRole.SAVE), spec("hf mf dump", "--file").type)
        assertEquals(ValueType.File(FileRole.LOAD), spec("data load", "--file").type)
        assertIs<ValueType.File>(spec("script run", "<filename>").type)
    }

    @Test
    fun hexLengthsFromDescriptions() {
        assertEquals(ByteLengths(listOf(4..4, 7..7)), HexLengths.fromDescription("UID, 4 or 7 bytes"))
        assertEquals(ByteLengths(listOf(1..31)), HexLengths.fromDescription("data, 1-31 hex bytes"))
        assertEquals(ByteLengths(listOf(6..6)), HexLengths.fromDescription("Key specified as 12 hex symbols"))
        assertEquals(ByteLengths(listOf(8..8, 16..16, 24..24)), HexLengths.fromDescription("key, 8|16|24 hex bytes"))
        assertEquals(ByteLengths(listOf(32..32, 65..65)), HexLengths.fromDescription("32-byte X or 65-byte uncompressed"))
        assertNull(HexLengths.fromDescription("ev1 password"))
    }

    @Test
    fun numberRanges() {
        assertEquals(ValueType.Number(8, 255), ValueTypes.classify("8..255", "", "x", false))
        assertEquals(ValueType.Number(0, 255), ValueTypes.classify("0-255", "", "x", false))
        assertEquals(ValueType.Number(1, null), ValueTypes.classify("1-", "", "x", false))
        assertEquals(ValueType.Number(unit = "мс"), ValueTypes.classify("ms", "", "x", false))
        assertEquals(ValueType.Number(fraction = true), ValueTypes.classify("dbl", "", "x", false))
        assertEquals(ValueType.Number(), ValueTypes.classify("0|1|...", "", "x", false))
        assertEquals(ValueType.Choice(listOf("plain", "mac", "encode"), default = "mac"), ValueTypes.classify("plain|mac(default)|encode", "", "x", false))
        assertEquals(ValueType.Text(hint = "hex|dec", suggestions = emptyList()), ValueTypes.classify("hex|dec", "", "x", false))
        assertEquals(ValueType.Text(hint = "name|cert|pubkey|path|skip", suggestions = listOf("skip")), ValueTypes.classify("name|cert|pubkey|path|skip", "", "x", false))
    }

    // ---- values ----

    @Test
    fun normalizeAndCheck() {
        val key = spec("hf mf rdbl", "-k")
        assertEquals("FFFFFFFFFFFF", Values.normalize(key.type, " ff ff:ff-ff ffff "))
        assertNull(Values.check(key, "ff ff ff ff ff ff"))
        // the help's <hex> is not always right (odd digit counts, file names): only a warning
        assertEquals(Severity.WARNING, Values.check(key, "fff")?.severity)
        assertEquals(Severity.WARNING, Values.check(key, "0x11")?.severity)
        assertEquals("ids.txt", Values.normalize(key.type, "ids.txt")) // not upper-cased: not hex
        assertEquals(Severity.ERROR, Values.check(key, "")?.severity)

        val blk = spec("hf mf rdbl", "--blk")
        assertEquals("16", Values.normalize(blk.type, "0x10"))
        assertNull(Values.check(blk, "0x10"))
        assertEquals(Severity.WARNING, Values.check(blk, "12z")?.severity)
        assertEquals(Severity.WARNING, Values.check(blk, "1.5")?.severity)
        assertEquals(Severity.WARNING, Values.check(blk, "-5")?.severity) // strtoull would wrap it
        assertEquals("0123", Values.normalize(blk.type, "0123")) // leading zeros stay (a PIN)
        assertEquals("1.5", Values.normalize(ValueType.Number(fraction = true), "1,5"))

        val ranged = blk.copy(type = ValueType.Number(8, 255))
        assertEquals(Severity.WARNING, Values.check(ranged, "7")?.severity)
        assertNull(Values.check(ranged, "255"))

        val id = spec("hf fmcos createdir", "--id")
        assertNull(Values.check(id, "3F00"))
        assertEquals(Severity.WARNING, Values.check(id, "3F0011")?.severity)

        val algo = spec("hf mfdes auth", "--algo")
        assertEquals("AES", Values.normalize(algo.type, "aes"))
        assertEquals(Severity.WARNING, Values.check(algo, "rsa")?.severity)

        val file = spec("data load", "--file")
        assertNull(Values.check(file, "my dump.bin"))
        assertNull(Values.check(file, "it's.bin")) // a quote inside a token is an ordinary character
        assertEquals(Severity.ERROR, Values.check(file, "it's mine.bin")?.severity) // quote and space: impossible
        assertEquals(Severity.ERROR, Values.check(file, "\"x")?.severity)
        assertEquals(Severity.ERROR, Values.check(file, "a\nb")?.severity)
        assertEquals(Severity.ERROR, Values.check(file, "a".repeat(2049))?.severity)

        // rawdemod reads its own arguments: a value with '-' in front can't be protected by `--`
        assertEquals(Severity.WARNING, Values.check(spec("data rawdemod", "<params>"), "-1", "data rawdemod")?.severity)
        assertNull(Values.check(spec("hf 14a raw", "<hex>"), "-1", "hf 14a raw").takeIf { it?.severity == Severity.ERROR })
    }

    @Test
    fun draftProblems() {
        val s = specs("hf mf rdbl")
        val d = CommandDraft.start("hf mf rdbl", s)
        assertEquals(listOf("--blk"), d.tags.map { it.label })
        assertFalse(DraftCheck.canRun(DraftCheck.problems(d, s))) // --blk has no value
        val ok = d.setValue(d.tags[0].id, "4")
        assertTrue(DraftCheck.problems(ok, s).isEmpty())
        val twice = ok.add(spec("hf mf rdbl", "--blk"), "5")
        assertEquals(Severity.WARNING, DraftCheck.problems(twice, s).single().problem.severity)
        val none = ok.remove(ok.tags[0].id)
        assertTrue(DraftCheck.problems(none, s).any { it.tagId == null && "--blk" in it.problem.message })
    }

    // ---- the line ----

    @Test
    fun buildsTheLine() {
        val s = specs("hf mf rdbl")
        var d = CommandDraft.start("hf mf rdbl", s)
        d = d.setValue(d.tags[0].id, "0x04")
        d = d.add(spec("hf mf rdbl", "-b"))
        d = d.add(spec("hf mf rdbl", "-k"), "a0a1 a2a3 a4a5")
        assertEquals("hf mf rdbl --blk 4 -b --key A0A1A2A3A4A5", CommandLine.build(d))

        val load = CommandDraft("data load", emptyList()).add(spec("data load", "--file"), "my dumps/a b.bin")
        assertEquals("data load --file \"my dumps/a b.bin\"", CommandLine.build(load))

        val empty = CommandDraft.start("hf mf rdbl", s)
        assertEquals("hf mf rdbl --blk <dec>", CommandLine.build(empty, placeholders = true))
        assertEquals("hf mf rdbl", CommandLine.build(empty))
    }

    @Test
    fun movingChangesTheOrder() {
        val raw = spec("hf 14a raw", "<hex>")
        var d = CommandDraft("hf 14a raw", emptyList()).add(raw, "11").add(raw, "22").add(spec("hf 14a raw", "-s"))
        d = d.move(d.tags[2].id, 0)
        assertEquals("hf 14a raw -s 11 22", CommandLine.build(d))
        d = d.moveBy(d.tags[1].id, 1)
        assertEquals("hf 14a raw -s 22 11", CommandLine.build(d))
        d = d.moveBy(d.tags[0].id, -5)
        assertEquals("hf 14a raw -s 22 11", CommandLine.build(d))
    }

    @Test
    fun tokenizesLikeTheClient() {
        assertEquals(listOf("-f", "a b.bin"), CommandLine.tokenize("-f \"a b.bin\""))
        assertEquals(listOf("-f", "a b.bin"), CommandLine.tokenize("-f 'a b.bin'"))
        // either quote ends quoting
        assertEquals(listOf("--hex", "0A"), CommandLine.tokenize("--hex \"0A'"))
        // what follows a closing quote in the same token is lost
        assertEquals(listOf("--hex", "0"), CommandLine.tokenize("--hex \"0\"A"))
        assertEquals(listOf("-f", "a b"), CommandLine.tokenize("-f \"a b\"x"))
        // a quote opens only at the start of a token; inside it is an ordinary character
        assertEquals(listOf("ab\"c", "d\""), CommandLine.tokenize("ab\"c d\""))
        assertEquals(listOf("--file=\"a", "b\""), CommandLine.tokenize("--file=\"a b\""))
        // either quote closes: `rem "it's ok"` prints `it ok"` on the host build
        assertEquals(listOf("it", "ok\""), CommandLine.tokenize("\"it's ok\""))
        assertEquals(listOf("a", "b"), CommandLine.tokenize("  a \t  b  "))
    }

    @Test
    fun positionalsStartingWithDashGoAfterDoubleDash() {
        // `rem <string> [<string>]...`: `rem -1` is refused as an unknown option, `rem -- -1` works
        val str = ParamSpec("<string>", null, null, emptyList(), "string", "", true, true, true, ValueType.Text())
        val v = ParamSpec("-v", "-v", null, emptyList(), null, "", false, false, false, ValueType.Flag)
        val d = CommandDraft("rem", emptyList()).add(str, "-1").add(v).add(str, "x")
        assertEquals("rem -v -- -1 x", CommandLine.build(d))
        // and it reads back the same
        val back = CommandLine.parse("rem", CommandLine.build(d), listOf(str, v))
        assertEquals(listOf("-v" to "", "<string>" to "-1", "<string>" to "x"), back.tags.map { it.label to it.value })
        // rawdemod reads its arguments itself: exactly the user's order, no `--`
        val p = spec("data rawdemod", "<params>")
        val rd = CommandDraft("data rawdemod", emptyList()).add(spec("data rawdemod", "--am")).add(p, "-1")
        assertEquals("data rawdemod --am -1", CommandLine.build(rd))
    }

    @Test
    fun aPrefixOfTwoAliasesIsAmbiguous() {
        val s = specs("hf vas reader")
        // --reader-private-key and --readerprivkey are both names of -k: getopt calls --reader ambiguous
        assertEquals(listOf("--reader", "x"), CommandLine.parse("hf vas reader", "--reader x", s).tags.filter { it.spec == null }.map { it.raw })
        assertEquals("--key", CommandLine.parse("hf vas reader", "--readerp x", s).tags.first().spec?.long)
    }

    @Test
    fun hexIntegersGoWithTheirPrefix() {
        val t = ValueTypes.classify("hex", "", "hw lcd", positional = false, longName = "--raw")
        assertEquals(ValueType.Hex(asInteger = true), t)
        assertEquals("0x1F", Values.normalize(t, "1f"))
        assertEquals(ValueType.File(FileRole.ANY), ValueTypes.classify("hex", "file with EM Tag IDs, one id per line", "lf em 410x brute", false, "--file"))
    }

    @Test
    fun parsesLinesBackIntoTags() {
        val s = specs("wiegand encode")
        val d = CommandLine.parse("wiegand encode", "wiegand encode -wH10301 --fc=101 --c 1337 -nv --bogus", s)
        assertEquals(listOf("-w" to "H10301", "--fc" to "101", "--cn" to "1337", "-n" to "", "-v" to ""),
            d.tags.filter { it.spec != null }.map { (it.spec!!.short ?: it.spec!!.long!!) to it.value })
        assertEquals(listOf("--bogus"), d.tags.filter { it.spec == null }.map { it.raw })

        // `--c` is a prefix of --cn only: getopt_long takes it
        val p = CommandLine.parse("hf 14a raw", "hf 14a raw -sc 9300 -t 100 11 22", specs("hf 14a raw"))
        // -s and -c are flags, so 9300 is the first raw byte string
        assertEquals(listOf("-s", "-c", "<hex>", "--timeout", "<hex>", "<hex>"), p.tags.map { it.label })
        assertEquals("hf 14a raw -s -c 9300 --timeout 100 11 22", CommandLine.build(p))
    }

    // examples the client itself would refuse: emv/cmdemv.c:1104 writes -D, the option is -d; in
    // `-sd -d ...` getopt (argtable3.c, short option with a required value) takes the second -d as
    // the value of the first, so the hex is left over
    private val brokenExamples = setOf(
        "emv genac -Daac 01020304",
        "hf 14a apdu -sd -d 00A404000E325041592E5359532E444446303100",
        // the help lists --mfc-license/--ka/--kb, not --mfc-keys/--mfc-blocks
        "hf mfdes createmfcmapping --aid 123456 --fid 01 --mfc-keys FFFFFFFFFFFF111111111111222222222222 --mfc-blocks 4,5,6,8",
    )

    @Test
    fun everyExampleOfTheFixtureReadsWithoutUnknownTokens() {
        for ((cmd, help) in dump) {
            val s = ParamSpecs.of(cmd, help)
            for (ex in help.examples) {
                if (!ex.text.startsWith(cmd) || ex.text in brokenExamples) continue
                val d = CommandLine.parse(cmd, ex.text, s)
                // -h is every command's own help, not a parameter: it stays a raw tag
                val raw = d.tags.filter { it.spec == null && it.raw != "-h" }
                assertTrue(raw.isEmpty(), "$cmd: `${ex.text}` left ${raw.map { it.raw }}")
                // reading the line we build gives the same tags back
                val again = CommandLine.parse(cmd, CommandLine.build(d), s)
                assertEquals(d.tags.map { t -> t.spec?.key to (t.spec?.let { Values.normalize(it.type, t.value) } ?: t.raw) },
                    again.tags.map { t -> t.spec?.key to (if (t.spec != null) t.value else t.raw) }, "$cmd: `${ex.text}`")
            }
        }
    }
}
