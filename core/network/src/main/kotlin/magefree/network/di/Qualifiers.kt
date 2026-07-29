package magefree.network.di

import javax.inject.Qualifier

/**
 * Marks the IO [kotlinx.coroutines.CoroutineDispatcher] used for network/session collection.
 * Injected (per `AGENTS.md`) rather than hard-coding `Dispatchers.IO` in a repository.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Marks the application-lifetime [kotlinx.coroutines.CoroutineScope] the connection repository uses
 * to keep its single source-of-truth `StateFlow` hot (`WhileSubscribed`). It is never cancelled for
 * the life of the process; the `SupervisorJob` keeps one failed collection from tearing it down.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
