package magefree.network.concurrent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

internal actual typealias ConcurrentMap<K, V> = ConcurrentHashMap<K, V>

internal actual typealias ConcurrentList<T> = CopyOnWriteArrayList<T>

internal actual typealias AtomicRef<T> = AtomicReference<T>
