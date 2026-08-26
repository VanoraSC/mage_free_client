package magefree.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import magefree.model.ServerTarget
import magefree.network.ServerRepository

/** Immutable UI state for the server list / add-server screen. */
data class ServerListUiState(
    /** True until the first snapshot of persisted servers has been observed. */
    val isLoading: Boolean = true,
    /** The persisted servers, in saved order. */
    val servers: List<ServerTarget> = emptyList(),
    /** The add/edit form state when it is open; `null` when closed. */
    val editor: ServerEditorState? = null,
) {
    /** True when there are no saved servers and loading has finished (drives the empty state). */
    val isEmpty: Boolean get() = !isLoading && servers.isEmpty()
}

/**
 * State of the add/edit server form. [original] distinguishes an edit (non-null, the server being
 * changed) from an add (null). Validation is pure and derived, so the screen enables Save purely from
 * [canSave].
 */
data class ServerEditorState(
    val original: ServerTarget? = null,
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val secure: Boolean = false,
) {
    val isEdit: Boolean get() = original != null

    /** Non-null when the host is invalid (blank), else null. */
    val hostError: String? get() = if (host.isBlank()) "Host is required" else null

    /** Non-null when the port is invalid (not an integer in 1..65535), else null. */
    val portError: String?
        get() =
            when (port.trim().toIntOrNull()) {
                null -> "Port must be a number"
                !in 1..65_535 -> "Port must be 1–65535"
                else -> null
            }

    /** True when both fields validate and the form can be saved. */
    val canSave: Boolean get() = hostError == null && portError == null

    /** The [ServerTarget] this form describes; only meaningful when [canSave]. */
    fun toTarget(): ServerTarget =
        ServerTarget(
            host = host.trim(),
            port = port.trim().toInt(),
            secure = secure,
            displayName = name.trim().takeIf { it.isNotBlank() },
        )
}

/**
 * MVVM ViewModel for the server list / add-server surface, driving the [ServerRepository].
 * Exposes a single immutable [ServerListUiState] as a [StateFlow]; every event is a function call.
 *
 * Picking a server is *not* VM state — selection is a navigation event the host handles, so the
 * screen raises it via a callback and this VM stays the owner of only the persisted list + form.
 */

class ServerListViewModel
    constructor(
        private val serverRepository: ServerRepository,
    ) : ViewModel() {
        private val editor = MutableStateFlow<ServerEditorState?>(null)

        val uiState: StateFlow<ServerListUiState> =
            combine(serverRepository.servers, editor) { servers, editorState ->
                ServerListUiState(isLoading = false, servers = servers, editor = editorState)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ServerListUiState(),
            )

        /** Open the form to add a new server. */
        fun openAddServer() {
            editor.value = ServerEditorState()
        }

        /** Open the form pre-filled to edit [target] (upsert keyed on its host/port). */
        fun openEditServer(target: ServerTarget) {
            editor.value =
                ServerEditorState(
                    original = target,
                    name = target.displayName.orEmpty(),
                    host = target.host,
                    port = target.port.toString(),
                    secure = target.secure,
                )
        }

        /** Close the form without saving. */
        fun dismissEditor() {
            editor.value = null
        }

        fun updateEditorName(value: String) = updateEditor { it.copy(name = value) }

        fun updateEditorHost(value: String) = updateEditor { it.copy(host = value) }

        fun updateEditorPort(value: String) = updateEditor { it.copy(port = value) }

        fun updateEditorSecure(value: Boolean) = updateEditor { it.copy(secure = value) }

        /**
         * Persist the current form if it validates, then close it. When editing changed the host/port
         * (the repository's identity key), the old entry is removed so the edit is a move, not a copy.
         */
        fun saveEditor() {
            val current = editor.value ?: return
            if (!current.canSave) return
            val target = current.toTarget()
            viewModelScope.launch {
                val previous = current.original
                if (previous != null && (previous.host != target.host || previous.port != target.port)) {
                    serverRepository.remove(previous)
                }
                serverRepository.add(target)
            }
            editor.value = null
        }

        /** Remove [target] from the saved list. */
        fun removeServer(target: ServerTarget) {
            viewModelScope.launch { serverRepository.remove(target) }
        }

        private inline fun updateEditor(transform: (ServerEditorState) -> ServerEditorState) {
            editor.value = editor.value?.let(transform)
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
