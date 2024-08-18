package helio.module

import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import org.tinylog.kotlin.Logger
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * An event handler that can fetch [DATA] based on [Event] type and pass it to the handler.
 */
class MultiEventHandler<DATA>(name: String) {
    val eventNode = EventNode.all(name)
    val dataGetters = mutableListOf<Pair<KClass<out Event>, (Event) -> DATA>>()

    /**
     * Registers a [DATA] getter for the [EVENT] type (including subtypes).
     * In case multiple are registered the last one will take precedence.
     */
    inline fun <reified EVENT : Event> dataFor(noinline block: EVENT.() -> DATA) {
        dataGetters.add(EVENT::class to { block.invoke(it as EVENT) })
    }

    /**
     * Registers an event handler for [EVENT] with [DATA] as argument, if none already exist.
     */
    inline fun <reified EVENT : Event> default(crossinline listener: EVENT.(data: DATA) -> Unit) {
        if (!eventNode.hasListener(EVENT::class.java)) {
            handle(listener)
        }
    }

    /**
     * Registers an event handler for [EVENT] with [DATA] as argument.
     */
    inline fun <reified EVENT : Event> handle(crossinline listener: EVENT.(data: DATA) -> Unit) {
        val dataGetter =
            dataGetters.reversed().firstOrNull { (clazz, _) -> EVENT::class.isSubclassOf(clazz) }?.second
        if (dataGetter == null) {
            Logger.error("No data getter for event ${EVENT::class.qualifiedName}.")
            return
        }
        eventNode.addListener(EVENT::class.java) { listener.invoke(it, dataGetter.invoke(it)) }
    }
}