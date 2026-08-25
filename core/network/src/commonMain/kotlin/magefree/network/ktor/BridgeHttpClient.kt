package magefree.network.ktor

import io.ktor.client.HttpClient

/**
 * The platform's Ktor [HttpClient] for the bridge session socket, with the WebSockets plugin
 * installed (story 0084).
 *
 * **Only the engine is platform-specific, and it has to be.** Ktor's client is multiplatform, but an
 * engine is not: `OkHttp` is named from a JVM-family artifact, and Ktor's engine-less `HttpClient { }`
 * overload — the one that finds an engine on the classpath — is itself declared only for the JVM. So
 * the engine choice sits at the platform edge, exactly like the SQLite driver in `:core:cards` and
 * the Room builder in `:core:decks`.
 *
 * Both current targets choose OkHttp. Android keeps it because swapping the engine that ships would
 * add an untested variable to a port; the JVM target takes the same one because a JVM host can, and
 * one engine across the module is one set of timeout, redirect and proxy behaviours rather than two.
 */
internal expect fun bridgeHttpClient(): HttpClient
