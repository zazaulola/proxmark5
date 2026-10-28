package io.github.zazaulola.proxmark5.parse

/**
 * A node of the client's command tree: a category (has [children]) or a command.
 *
 * The tree comes from the client itself (walkCommandsRecursive() in client/src/cmdparser.c),
 * so it always matches the build in the app, including what the connected device supports.
 */
class CommandNode(
    val name: String,
    /** Full path from the top, e.g. ["hf", "mf", "dump"]. */
    val path: List<String>,
    /** Description without ANSI codes and without the `{ ... }` the client puts around categories. */
    val help: String,
    /** The client lets this run right now; offline commands stay available without a device. */
    val available: Boolean,
    val children: List<CommandNode>,
) {
    val isGroup get() = children.isNotEmpty()

    /** The command line as typed, e.g. "hf mf dump". */
    val line get() = path.joinToString(" ")

    fun child(name: String): CommandNode? = children.firstOrNull { it.name == name }

    /** Walks down [names] from this node, or null if the path leaves the tree. */
    fun find(names: List<String>): CommandNode? {
        var node: CommandNode = this
        for (n in names) node = node.child(n) ?: return null
        return node
    }

    /** Every command below this node (not the categories), in tree order. */
    fun leaves(): Sequence<CommandNode> = sequence {
        for (c in children) {
            if (c.isGroup) yieldAll(c.leaves()) else yield(c)
        }
    }
}

object CommandTree {
    /**
     * Parses what the native layer reports: one command per line, and for every level on the
     * way down to it `name TAB help TAB 1|0` (1 = available), the levels joined by TABs.
     * Returns the nameless root whose children are the top-level categories and commands.
     */
    fun parse(text: String): CommandNode {
        val root = Builder("", emptyList())
        for (row in text.lineSequence()) {
            if (row.isBlank()) continue
            val cols = row.split('\t')
            if (cols.size % 3 != 0) continue
            var node = root
            for (i in cols.indices step 3) {
                val name = cols[i]
                if (name.isEmpty()) break
                val child = node.children.getOrPut(name) { Builder(name, node.path + name) }
                child.help = clean(cols[i + 1])
                child.available = cols[i + 2] == "1"
                node = child
            }
        }
        return root.build(true)
    }

    /** Case-insensitive search of the commands: every word of [query] must be in the line or the description. */
    fun search(root: CommandNode, query: String, limit: Int = 100): List<CommandNode> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return root.leaves().filter { n ->
            val hay = (n.line + " " + n.help).lowercase()
            words.all { hay.contains(it) }
        }.take(limit).toList()
    }

    /** "{ High frequency commands... }" -> "High frequency commands" */
    internal fun clean(help: String): String {
        var s = Ansi.strip(help).trim()
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length - 1).trim()
        return s.removeSuffix("...").removeSuffix("…").trim()
    }

    private class Builder(val name: String, val path: List<String>) {
        var help = ""
        var available = true
        val children = LinkedHashMap<String, Builder>()

        fun build(parentAvailable: Boolean): CommandNode {
            val avail = parentAvailable && available
            return CommandNode(name, path, help, avail, children.values.map { it.build(avail) })
        }
    }
}
