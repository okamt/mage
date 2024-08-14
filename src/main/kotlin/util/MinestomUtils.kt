package helio.util

import net.minestom.server.event.Event
import net.minestom.server.event.EventNode

inline fun <reified E : Event> EventNode<in E>.listenWith(crossinline listener: E.() -> Unit) =
    addListener(E::class.java) { it.apply(listener) }