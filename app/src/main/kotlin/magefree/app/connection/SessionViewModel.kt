package magefree.app.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import magefree.network.ConnectionRepository
import javax.inject.Inject

/**
 * The shell's one session **command** (story 0047), kept apart from [ConnectionStatusViewModel], which
 * only *observes* state for the status strip.
 *
 * `AppRoot` binds [signOut] to the root graph's sign-out transition. It is the deliberate exit story
 * 0046 built: [ConnectionRepository.signOut] drops the connect intent **and** tells the bridge to end
 * the upstream XMage session now (a `Logout`), rather than the silent teardown a backgrounding or a
 * dropped socket produces, which leaves the session parked for a reconnect to resume.
 *
 * Its counterpart — establishing a session in the first place — deliberately does **not** live here:
 * `:feature:connect`'s `SignInViewModel` owns that, because it is the only place credentials exist.
 */
@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        private val connectionRepository: ConnectionRepository,
    ) : ViewModel() {
        /** End the session deliberately. Safe to call with nothing connected — it clears an empty intent. */
        fun signOut() {
            viewModelScope.launch { connectionRepository.signOut() }
        }
    }
