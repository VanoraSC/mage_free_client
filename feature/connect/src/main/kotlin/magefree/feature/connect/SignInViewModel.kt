package magefree.feature.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import magefree.model.Credentials
import magefree.model.ServerTarget
import magefree.network.ConnectionRepository

/** The credential form fields, kept separate from the live connection phase. */
private data class SignInForm(
    val username: String = "",
    val password: String = "",
)

/**
 * Immutable UI state for the sign-in screen.
 *
 * @param server the server being signed in to, or null before one is bound.
 * @param username / password the current form input (password is never logged/persisted).
 * @param phase the live [ConnectPhase] derived from the repository's connection status.
 * @param detail the failure diagnostic for the current [phase] (e.g. `server=… bridge=…` on a version
 *   mismatch, an auth message, or a transport reason), shown by the failure surface when present;
 *   null in non-failure phases. Never a credential/secret.
 */
data class SignInUiState(
    val server: ServerTarget? = null,
    val username: String = "",
    val password: String = "",
    val phase: ConnectPhase = ConnectPhase.Idle,
    val detail: String? = null,
) {
    /** The form is submittable when a server is bound, a username is present, and nothing is in flight. */
    val canSubmit: Boolean
        get() = server != null && username.isNotBlank() && phase == ConnectPhase.Idle
}

/**
 * MVVM ViewModel for the sign-in surface. It drives story 0017's [ConnectionRepository]: [connect]
 * calls [ConnectionRepository.connect] with the bound server and entered [Credentials], and the UI
 * renders the resulting [ConnectPhase] + failure [SignInUiState.detail] observed from story 0019's
 * detail-carrying [ConnectionRepository.connectionStatus].
 *
 * The bound [ServerTarget] arrives via [bind] (the flow's coordinator seeds it) rather than a nav
 * argument, keeping the VM usable both under Hilt and directly in hermetic tests.
 *
 * Credentials are held only in transient form state and sent per sign-in; nothing is logged or
 * persisted (see `AGENTS.md` / the story's credential rule). Token/secure storage is deliberately
 * deferred to a later decision.
 */

class SignInViewModel
    constructor(
        private val connectionRepository: ConnectionRepository,
    ) : ViewModel() {
        private val server = MutableStateFlow<ServerTarget?>(null)
        private val form = MutableStateFlow(SignInForm())

        val uiState: StateFlow<SignInUiState> =
            combine(server, form, connectionRepository.connectionStatus) { srv, formState, status ->
                SignInUiState(
                    server = srv,
                    username = formState.username,
                    password = formState.password,
                    phase = status.toConnectPhase(),
                    detail = status.detail,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = SignInUiState(),
            )

        /** Seed the server to sign in to; idempotent so it can be called on (re)composition. */
        fun bind(target: ServerTarget) {
            server.value = target
        }

        fun updateUsername(value: String) {
            form.value = form.value.copy(username = value)
        }

        fun updatePassword(value: String) {
            form.value = form.value.copy(password = value)
        }

        /** Open a session against the bound server with the entered credentials. No-op if not submittable. */
        fun connect() {
            val target = server.value ?: return
            val current = form.value
            if (current.username.isBlank()) return
            connectionRepository.connect(
                server = target,
                credentials = Credentials(username = current.username, password = current.password.ifBlank { null }),
            )
        }

        /** Re-attempt the last connect (used by the failure surfaces' retry choice). */
        fun retry() {
            connectionRepository.retry()
        }

        /**
         * Tear the session down and return to the [ConnectPhase.Idle] form.
         *
         * This is the app's sign-out: every route into it is the player leaving on purpose — the
         * failure surfaces' Cancel, the sign-in screen's Back, and the session-lost re-authenticate
         * choice. So it takes the deliberate teardown ([ConnectionRepository.signOut]), which tells the
         * bridge to drop the upstream XMage session now rather than park it for the resume window
         * (story 0046); a drop or a backgrounding never comes through here.
         */
        fun cancel() {
            viewModelScope.launch { connectionRepository.signOut() }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
