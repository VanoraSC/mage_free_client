package magefree.app.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Indirection over the standard [Dispatchers] so business logic never hard-codes `Dispatchers.*`
 * (see `AGENTS.md`). Inject this into ViewModels/repositories and take dispatchers from here; tests
 * substitute a `TestDispatcher`-backed implementation to run coroutines deterministically.
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

/** Production [DispatcherProvider] delegating to the real [Dispatchers]. Bound via Hilt. */
@Singleton
class DefaultDispatcherProvider
    @Inject
    constructor() : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
    }
