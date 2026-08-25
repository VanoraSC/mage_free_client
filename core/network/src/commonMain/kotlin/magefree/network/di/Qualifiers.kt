package magefree.network.di

import org.koin.core.qualifier.named

/*
 * Koin qualifiers for the two bindings whose *type* does not identify them (story 0081).
 *
 * Hilt distinguished these with `@Qualifier` annotation classes resolved at compile time; Koin
 * resolves by name at runtime, so they are constants rather than annotations. Declaring them here as
 * `val`s — instead of writing `named("io")` at each site — keeps a typo a compile error rather than
 * a missing binding discovered on some screen much later, which is the failure mode Koin trades
 * compile-time safety for.
 */

/**
 * The IO [kotlinx.coroutines.CoroutineDispatcher] used for network/session collection. Injected
 * (per `AGENTS.md`) rather than hard-coding `Dispatchers.IO` in a repository.
 */
val IoDispatcher = named("IoDispatcher")

/**
 * The application-lifetime [kotlinx.coroutines.CoroutineScope] the connection repository uses to
 * keep its single source-of-truth `StateFlow` hot (`WhileSubscribed`). It is never cancelled for the
 * life of the process; the `SupervisorJob` keeps one failed collection from tearing it down.
 */
val ApplicationScope = named("ApplicationScope")
