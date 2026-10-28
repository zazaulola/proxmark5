package io.github.zazaulola.proxmark5.session

/**
 * What Stop may do to a command, and when it gives up waiting. A command that polls the keyboard ends
 * on Enter (plus a BREAK to the device for the loops that wait on it). One that never looks at the
 * keyboard can only be ended by ending the client process.
 */
object StopPolicy {
    /** Commands that ignore Enter: they run to the end or until the process dies. */
    private val STUBBORN = listOf(
        listOf("hf", "mf", "hardnested"),
        listOf("hf", "mfdes", "chk"),
        listOf("hf", "mfdes", "detect"),
    )

    /** Time a command that polls the keyboard gets to notice Stop. */
    const val GRACE_MS = 6_000L

    /** Time for a command known not to look at the keyboard: just enough for a BREAK to land. */
    const val STUBBORN_GRACE_MS = 1_500L

    /** The UI's own backstop: it kills the service process this long after the service's deadline. */
    const val UI_BACKSTOP_MS = 2_000L

    private val WS = Regex("\\s+")

    /** True for the commands Enter can't stop (see [STUBBORN]). */
    fun isStubborn(cmd: String): Boolean {
        val words = words(cmd)
        return STUBBORN.any { s -> words.size >= s.size && words.subList(0, s.size) == s }
    }

    /** How long to wait for [cmd] to end after Stop before acting on it. */
    fun graceMs(cmd: String): Long = if (isStubborn(cmd)) STUBBORN_GRACE_MS else GRACE_MS

    /**
     * Whether the session may end a running command by killing the client process without asking.
     * Only for commands known to ignore Enter; for any other one the user is asked ("restart the
     * client?"), because a hard kill in the middle of a card write can leave the card half written.
     */
    fun mayKillAutomatically(cmd: String): Boolean = isStubborn(cmd)

    /**
     * Whether Stop, leaving the session and killing the process are allowed in [link]. They are not
     * while a firmware is being written: nothing in the flasher listens for Enter, and a kill in the
     * middle of a write can leave the device unbootable.
     */
    fun mayInterrupt(link: LinkState): Boolean = !link.uninterruptible

    // the words that name the command: lower case, up to the first option
    private fun words(cmd: String) = cmd.trim().lowercase().split(WS).takeWhile { !it.startsWith("-") }.filter { it.isNotEmpty() }
}
