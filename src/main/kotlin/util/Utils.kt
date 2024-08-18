package helio.util

import java.util.*

inline fun <reified T : Enum<T>> T.all(): EnumSet<T> = EnumSet.allOf(T::class.java)

inline fun <reified T : Enum<T>?> all(): EnumSet<T> = EnumSet.allOf(T::class.java)

val <T : Enum<T>> T.only: EnumSet<T>
    get() = EnumSet.of(this)

infix fun <T : Enum<T>> T.and(rhs: T): EnumSet<T> = EnumSet.of(this, rhs)

infix fun <T : Enum<T>> EnumSet<T>.and(rhs: T): EnumSet<T> {
    this.add(rhs)
    return this
}

infix fun Boolean.orRun(block: () -> Unit) {
    if (!this) {
        block()
    }
}
