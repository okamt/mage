package helio.module

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.bladehunt.kotstom.coroutines.MinestomDispatcher
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * A wrapper for [EventNode] that can fetch [DATA] for the handler based on [Event] type, among other things.
 */
class MultiEventHandler<DATA>(val name: String) {
    val eventNode = EventNode.all(name)
    val dataGetters = mutableListOf<Pair<KClass<out Event>, (Event) -> DATA>>()
    val filters = mutableListOf<Pair<KClass<out Event>, Event.(DATA) -> Boolean>>()

    /**
     * Registers a [DATA] getter for the [EVENT] type (including subtypes).
     * If multiple are registered the last one will take precedence.
     */
    inline fun <reified EVENT : Event> dataFor(noinline block: EVENT.() -> DATA) {
        dataGetters.add(EVENT::class to { block.invoke(it as EVENT) })
    }

    /**
     * Registers an event handler for [EVENT] with [DATA] as argument, if none already exist.
     *
     * @see handle
     */
    inline fun <reified EVENT : Event> default(crossinline listener: EVENT.(data: DATA) -> Unit) {
        if (!eventNode.hasListener(EVENT::class.java)) {
            handle(listener)
        }
    }

    /**
     * Registers an async event handler for [EVENT] with [DATA] as argument.
     *
     * @see handle
     */
    inline fun <reified EVENT : Event> handleAsync(crossinline listener: suspend EVENT.(data: DATA) -> Unit) {
        handle<EVENT> {
            CoroutineScope(MinestomDispatcher).launch {
                listener(it)
            }
        }
    }

    /**
     * Registers a filter predicate for [EVENT] handlers. Only [EVENT]s that satisfy all registered predicates for
     * [EVENT] and its superclasses will have their handlers executed. Handlers registered before a filter will not
     * be affected by it.
     *
     * @see filterAll
     */
    inline fun <reified EVENT : Event> filter(noinline block: EVENT.(data: DATA) -> Boolean) {
        @Suppress("UNCHECKED_CAST")
        filters.add(EVENT::class to (block as Event.(DATA) -> Boolean))
    }

    /**
     * Registers a filter predicate for all event handlers.
     *
     * @see filter
     */
    fun filterAll(block: Event.(data: DATA) -> Boolean) {
        filters.add(Event::class to block)
    }

    /**
     * Transforms this [MultiEventHandler] from [DATA] to [NEW_DATA]. Will transform all [dataGetters] using [block].
     * Existing [filters] will work the same, using the old [dataGetters]. The current [eventNode] will be set as
     * child of the new [eventNode].
     *
     * @throws [IllegalStateException] if no data getter exists for one of the current filters
     */
    fun <NEW_DATA> transform(block: (data: DATA) -> NEW_DATA): MultiEventHandler<NEW_DATA> {
        val new = MultiEventHandler<NEW_DATA>(name)
        new.eventNode.addChild(eventNode)
        new.dataGetters.addAll(dataGetters.map {
            it.first to { event: Event -> block(it.second(event)) }
        })
        new.filters.addAll(filters.map { pair ->
            pair.first to {
                val data =
                    checkNotNull(this@MultiEventHandler.getDataGetter(this::class)) { "No data getter for event ${this::class}" }
                        .invoke(this)
                pair.second.invoke(this, data)
            }
        })
        return new
    }

    /**
     * Gets the data getter for [EVENT].
     * If multiple are registered the last one will take precedence.
     */
    inline fun <reified EVENT : Event> getDataGetter(): ((Event) -> DATA)? =
        getDataGetter(EVENT::class)

    /**
     * Gets the data getter for [eventClass].
     * If multiple are registered the last one will take precedence.
     */
    fun getDataGetter(eventClass: KClass<out Event>): ((Event) -> DATA)? =
        dataGetters.reversed().firstOrNull { (clazz, _) -> eventClass.isSubclassOf(clazz) }?.second

    /**
     * Registers an event handler for [EVENT] with [DATA] as argument.
     *
     * @throws IllegalArgumentException if no data getter exists for [EVENT]
     */
    inline fun <reified EVENT : Event> handle(crossinline listener: EVENT.(data: DATA) -> Unit) {
        val dataGetter =
            requireNotNull(
                dataGetters.reversed().firstOrNull { (clazz, _) -> EVENT::class.isSubclassOf(clazz) }?.second
            )
            { "No data getter for event ${EVENT::class.qualifiedName}." }
        val filters = filters.filter { EVENT::class.isSubclassOf(it.first) }
        val filter = { event: EVENT, data: DATA ->
            filters.all { it.second.invoke(event, data) }
        }
        eventNode.addListener(EVENT::class.java) {
            val data = dataGetter.invoke(it)
            if (!filter(it, data)) return@addListener
            listener.invoke(it, data)
        }
    }
}