package helio.util

import java.util.*

inline fun <reified ENUM : Enum<ENUM>?> enumSetOfAll(): EnumSet<ENUM> = EnumSet.allOf(ENUM::class.java)
