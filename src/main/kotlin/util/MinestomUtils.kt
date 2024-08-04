package helio.util

import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import kotlin.reflect.KClass

fun <T : Event, E : T> EventNode<T>.addListener(eventType: KClass<E>, listener: E.() -> Unit) =
    addListener(eventType.java) { it.apply(listener) }
