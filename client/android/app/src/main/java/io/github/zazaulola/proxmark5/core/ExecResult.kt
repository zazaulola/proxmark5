package io.github.zazaulola.proxmark5.core

/**
 * Result of one client command. [code] is the client's own return value, or one of
 * io.github.zazaulola.proxmark5.session.ResultCode when the command never got a normal answer.
 */
data class ExecResult(val code: Int, val grabbed: String) {
    val ok get() = code == 0
}
