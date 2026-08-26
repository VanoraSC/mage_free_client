package magefree.network.concurrent

/*
 * The thread-safe primitives this module's session layer needs, expressed so they can live in
 * `commonMain`.
 *
 * **These are `expect`s whose `actual`s are typealiases to the `java.util.concurrent` classes the
 * pre-port code named directly**, so on every current target the bytecode, the memory model and the
 * performance are unchanged — a `ConcurrentHashMap` really is a `ConcurrentHashMap`, not a
 * lock-guarded `mutableMapOf()` wearing its name. What changes is that `commonMain` no longer
 * references `java.*`, and a future non-JVM target has these three declarations to satisfy rather
 * than a search through the reconnect loop.
 *
 * The alternatives were considered and rejected: `kotlin.concurrent.atomics` is still
 * `@ExperimentalAtomicApi` and would mean rewriting the correlation registry as a CAS loop over an
 * immutable map; `kotlinx.atomicfu` is documented as "not supported for general usage"; and
 * Touchlab's `ConcurrentMutableMap` has no JVM source set at all — it is one global lock, so every
 * read on the socket's inbound path would block.
 *
 * The type-parameter names are `E`/`V`, not `T`. A typealias `actual` must match the aliased class
 * down to its type-parameter *names*, and these are the names the JDK classes use.
 */

/**
 * `java.util.concurrent.ConcurrentHashMap` on the JVM-family targets.
 *
 * The supertype is deliberately absent: `ConcurrentHashMap.keys` returns its own `KeySetView`, not
 * `MutableSet<K>`, so an `expect class … : MutableMap<K, V>` cannot be satisfied by a typealias to
 * it. Only the operations the correlation registry actually performs are declared.
 */
internal expect class ConcurrentMap<K : Any, V : Any>() {
    fun put(
        key: K,
        value: V,
    ): V?

    fun remove(key: K): V?

    fun clear()

    val values: MutableCollection<V>
}

/** `java.util.concurrent.CopyOnWriteArrayList` on the JVM-family targets. */
internal expect class ConcurrentList<E>() : MutableList<E>

/** `java.util.concurrent.atomic.AtomicReference` on the JVM-family targets. */
internal expect class AtomicRef<V>(
    initialValue: V,
) {
    fun get(): V

    fun set(newValue: V)

    fun getAndSet(newValue: V): V
}
