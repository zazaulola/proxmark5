package io.github.zazaulola.proxmark5.ui.params

import android.app.Application
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.zazaulola.proxmark5.core.HelpRepository
import io.github.zazaulola.proxmark5.parse.CommandHelp
import io.github.zazaulola.proxmark5.parse.params.CommandDraft
import io.github.zazaulola.proxmark5.parse.params.DraftCodec
import io.github.zazaulola.proxmark5.parse.params.ParamSpec
import io.github.zazaulola.proxmark5.parse.params.ParamSpecs
import kotlinx.coroutines.launch

/** Where the help of a command stands. */
sealed interface HelpState {
    data object Loading : HelpState

    class Ready(val help: CommandHelp, val specs: List<ParamSpec>) : HelpState

    /**
     * Not in the APK's dump: [noDump] when the APK has no dump at all (a local build). The live
     * client can't fill in: with its output grabbed, `-h` loses the usage and options lines (they
     * are printed past the grab), so then only the line can be edited as text.
     */
    class Missing(val noDump: Boolean) : HelpState
}

/**
 * The parameter editor's state: the help of the commands opened, and a draft of tags for each.
 * Drafts also go to the saved state, so they come back when Android kills the UI process while
 * the user is in another app (the client's process lives on; this one may not).
 */
class ParamsViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val help = mutableStateMapOf<String, HelpState>()
    private val drafts = mutableStateMapOf<String, CommandDraft>()

    init {
        HelpRepository.warmUp(app)
    }

    fun helpState(command: String): HelpState = help[command] ?: HelpState.Loading

    /** Reads the help of [command] from the APK's dump, once. */
    fun load(command: String) {
        if (help[command] is HelpState.Ready) return
        help[command] = HelpState.Loading
        viewModelScope.launch {
            help[command] = when (val r = HelpRepository.lookup(getApplication<Application>(), command)) {
                is HelpRepository.Lookup.Found -> ready(command, r.help)
                HelpRepository.Lookup.NoDump -> HelpState.Missing(noDump = true).also { textDraft(command) }
                HelpRepository.Lookup.NotInDump -> HelpState.Missing(noDump = false).also { textDraft(command) }
            }
        }
    }

    private fun ready(command: String, h: CommandHelp): HelpState.Ready {
        val specs = ParamSpecs.of(command, h)
        if (command !in drafts) {
            val stored = saved.get<ArrayList<String>>(KEY_PREFIX + command)
            drafts[command] = if (stored != null) DraftCodec.decode(command, stored, specs) else CommandDraft.start(command, specs)
        }
        return HelpState.Ready(h, specs)
    }

    fun draft(command: String): CommandDraft? = drafts[command]

    fun update(draft: CommandDraft) {
        drafts[draft.command] = draft
        persist(draft)
    }

    /** The draft of a command whose help is missing: raw text tags only, restored like the others. */
    private fun textDraft(command: String): CommandDraft {
        drafts[command]?.let { return it }
        val stored = saved.get<ArrayList<String>>(KEY_PREFIX + command)
        val d = if (stored != null) DraftCodec.decode(command, stored, emptyList()) else CommandDraft(command, emptyList())
        drafts[command] = d
        return d
    }

    /** Back to the required parameters only. */
    fun reset(command: String) {
        val specs = (help[command] as? HelpState.Ready)?.specs.orEmpty()
        update(CommandDraft.start(command, specs))
    }

    private fun persist(draft: CommandDraft) {
        val cmd = draft.command
        saved[KEY_PREFIX + cmd] = DraftCodec.encode(draft)
        // keep the saved state small: the last few commands only
        val order = (saved.get<ArrayList<String>>(KEY_ORDER) ?: arrayListOf()).apply {
            remove(cmd)
            add(cmd)
        }
        while (order.size > MAX_SAVED) saved.remove<ArrayList<String>>(KEY_PREFIX + order.removeAt(0))
        saved[KEY_ORDER] = order
    }

    private companion object {
        const val KEY_PREFIX = "params.draft:"
        const val KEY_ORDER = "params.order"
        const val MAX_SAVED = 12
    }
}
