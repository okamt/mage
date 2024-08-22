package helio.module

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.bladehunt.kotstom.coroutines.MinestomDispatcher
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.full.functions
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.starProjectedType

/**
 * Interface for easily defining event handlers that may take [DATA]. Uses reflection to find relevant functions.
 *
 * When called, [MultiEventHandler.registerAllEventHandlers] will look for these functions in your class, where `E` is [Event] or a subtype:
 *
 * - `E.handle()`
 * - `E.handle(`[DATA]`)`
 * - `E.getData(): `[DATA]
 * - `E.filter(): `[Boolean]
 *
 * Also supports `suspend` event handlers through [MinestomDispatcher].
 *
 * Additionally, if [DataStore] is implemented, and `DataStore.KEY == `[DATA], event handlers may have a `DataStore.DATA` parameter.
 */
interface MultiEventHandler<DATA>

inline fun <reified T> T.getTypeArgument(nth: Int): KClass<*> =
    (if (this is KType) this else this::class.allSupertypes.first { it.classifier == T::class })
        .arguments[nth].type!!.classifier as KClass<*>

fun MultiEventHandler<*>.getEventHandlers(eventClass: KClass<out Event>): List<KFunction<*>> {
    val dataStore = this::class.allSupertypes.firstOrNull { it.classifier == DataStore::class }
    val allowsData = (dataStore != null) && (dataStore.getTypeArgument(0) == getTypeArgument(0))

    return this::class.functions.filter {
        it.extensionReceiverParameter?.type?.isSubtypeOf(eventClass.starProjectedType) == true && it.name == "handle"
    }.map {
        check(
            it.parameters.size == 2 ||
                    (it.parameters.size == 3 && it.parameters[2].type.classifier == getTypeArgument(0)) ||
                    (allowsData && it.parameters.size == 3 && it.parameters[2].type.classifier == dataStore.getTypeArgument(
                        1
                    ))
        ) {
            if (allowsData)
                "Event handler $it must have no parameters, one parameter of type ${getTypeArgument(0).qualifiedName} " +
                        "or one parameter of type ${dataStore.getTypeArgument(1).qualifiedName}."
            else
                "Event handler $it must have no parameters or one parameter of type ${getTypeArgument(0).qualifiedName}."
        }

        it
    }
}

internal fun <DATA> MultiEventHandler<DATA>.getEventDataGetters(eventClass: KClass<out Event>): List<KFunction<DATA>> =
    this::class.functions.filter {
        it.extensionReceiverParameter?.type?.let(eventClass.starProjectedType::isSubtypeOf) == true &&
                it.name == "getData" &&
                it.returnType.classifier == getTypeArgument(0)
    }.map {
        check(it.parameters.size == 2) { "Event data getter $it must have no parameters." }

        @Suppress("UNCHECKED_CAST")
        it as KFunction<DATA>
    }

internal fun MultiEventHandler<*>.getEventFilters(eventClass: KClass<out Event>): List<KFunction<Boolean>> =
    this::class.functions.filter {
        it.extensionReceiverParameter?.type?.let(eventClass.starProjectedType::isSubtypeOf) == true &&
                it.name == "filter" &&
                it.returnType.classifier == Boolean::class
    }.map {
        check(it.parameters.size == 2) { "Event filter $it must have no parameters." }

        @Suppress("UNCHECKED_CAST")
        it as KFunction<Boolean>
    }

/**
 * @see MultiEventHandler
 */
fun <DATA> MultiEventHandler<DATA>.registerAllEventHandlers(eventNode: EventNode<Event>) {
    getEventHandlers(Event::class).forEach { eventHandler ->
        @Suppress("UNCHECKED_CAST") val eventClass =
            eventHandler.extensionReceiverParameter!!.type.classifier as KClass<out Event>

        assert(eventHandler.parameters[0].type.classifier == this::class)
        assert(eventHandler.parameters[1].type.classifier == eventClass)

        val dataGetter = requireNotNull(
            getEventDataGetters(eventClass).reversed().firstOrNull()
        ) { "No data getter for event ${eventClass.qualifiedName}." }

        val filters = getEventFilters(eventClass)
        val filter = { event: Event ->
            filters.all { it.call(this, event) }
        }

        fun KFunction<*>.callMaybeSuspend(vararg args: Any?) {
            if (eventHandler.isSuspend) {
                CoroutineScope(MinestomDispatcher).launch {
                    callSuspend(*args)
                }
            } else {
                call(*args)
            }
        }

        var callEvent = if (eventHandler.parameters.size == 3)
            if (eventHandler.parameters[2] == getTypeArgument(0))
                { event: Event ->
                    eventHandler.callMaybeSuspend(this, event, dataGetter.call(this, event))
                }
            else
                { event: Event ->
                    @Suppress("UNCHECKED_CAST")
                    (this as DataStore<DATA, Any>).withData(dataGetter.call(this, event)) {
                        eventHandler.callMaybeSuspend(this@registerAllEventHandlers, event, this)
                    }
                }
        else { event: Event ->
            eventHandler.callMaybeSuspend(this, event)
        }

        eventNode.addListener(eventClass.java) { event ->
            if (!filter(event)) {
                return@addListener
            }

            callEvent(event)
        }
    }
}