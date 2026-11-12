package ai.rever.boss.plugin.dynamic.fluckbrowser.autofill

/**
 * Attach/detach bookkeeping that tolerates attach finishing after detach.
 *
 * [reserve] before wiring, [publish] after; a [detach] in between cancels the reservation and
 * [publish] then returns false so the caller can undo what it wired. Entries are removed
 * explicitly; nothing here relies on garbage collection.
 */
internal class TabRegistry<K : Any, T : Any> {
    private sealed class State<out T> {
        object Pending : State<Nothing>()

        object Cancelled : State<Nothing>()

        class Live<T>(
            val value: T,
        ) : State<T>()
    }

    private val states = HashMap<K, State<T>>()

    /** True when the caller should wire [key]; false when it is already live or being wired. */
    @Synchronized
    fun reserve(key: K): Boolean =
        when (states[key]) {
            null, State.Cancelled -> {
                states[key] = State.Pending
                true
            }
            else -> false
        }

    /** True when [value] is now live; false when [key] was detached meanwhile (caller undoes its wiring). */
    @Synchronized
    fun publish(
        key: K,
        value: T,
    ): Boolean =
        if (states[key] === State.Pending) {
            states[key] = State.Live(value)
            true
        } else {
            states.remove(key)
            false
        }

    /** Drops a failed reservation. */
    @Synchronized
    fun abandon(key: K) {
        if (states[key] === State.Pending || states[key] === State.Cancelled) states.remove(key)
    }

    /** Detaches [key]: a pending wiring is cancelled, a live one is removed and returned. */
    @Synchronized
    fun detach(key: K): T? =
        when (val s = states[key]) {
            is State.Live -> {
                states.remove(key)
                s.value
            }
            State.Pending -> {
                states[key] = State.Cancelled
                null
            }
            else -> null
        }

    @Synchronized
    operator fun get(key: K): T? = (states[key] as? State.Live<T>)?.value

    @Synchronized
    fun live(): List<T> = states.values.mapNotNull { (it as? State.Live<T>)?.value }

    @Synchronized
    fun liveCountExcept(key: K): Int = states.count { (k, v) -> k !== key && v is State.Live }

    @Synchronized
    fun clear() = states.clear()
}
