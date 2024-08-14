package helio.util

import java.util.*

inline fun <reified ENUM : Enum<ENUM>?> enumSetOfAll(): EnumSet<ENUM> = EnumSet.allOf(ENUM::class.java)

val <T : Enum<T>> T.only
    get() = EnumSet.of(this)

infix fun <T : Enum<T>> T.and(rhs: T): EnumSet<T> = EnumSet.of(this, rhs)

infix fun <T : Enum<T>> EnumSet<T>.and(rhs: T): EnumSet<T> {
    this.add(rhs)
    return this
}
