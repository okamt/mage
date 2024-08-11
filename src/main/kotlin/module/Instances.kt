package helio.module

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.bladehunt.kotstom.DimensionTypeRegistry
import net.bladehunt.kotstom.InstanceManager
import net.bladehunt.kotstom.SchedulerManager
import net.bladehunt.kotstom.coroutines.MinestomDispatcher
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.Event
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.event.player.PlayerStartSneakingEvent
import net.minestom.server.event.player.PlayerStopSneakingEvent
import net.minestom.server.event.trait.InstanceEvent
import net.minestom.server.instance.IChunkLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.registry.DynamicRegistry
import net.minestom.server.tag.Tag
import net.minestom.server.world.DimensionType
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

val INSTANCE_DEF_ID_TAG = Tag.String("instanceId")
val INSTANCE_DATA_ID_TAG = Tag.UUID("instanceDataId")

/**
 * The instances module.
 */
@BuiltinModule(BuiltinModuleType.CORE)
object Instances : ServerModule("instances"), FeatureRegistry<InstanceDefinition<*>> {
    private val map: MutableMap<FeatureDefinition.Id, InstanceDefinition<*>> = mutableMapOf()

    private val events = listOf(
        PlayerSpawnEvent::class,
        PlayerStartSneakingEvent::class,
        PlayerStopSneakingEvent::class,
        PlayerMoveEvent::class,
    )

    private fun getInstanceFromEvent(event: Event): Instance = when (event) {
        is InstanceEvent -> event.instance
        else -> error("Could not get Instance from event ${event::class.qualifiedName}.")
    }

    // TODO: make these generic as well
    fun getInstanceDefinition(id: FeatureDefinition.Id): InstanceDefinition<*>? =
        map[id]

    fun getInstanceDefinition(instance: Instance): InstanceDefinition<*> {
        val instanceDefId =
            FeatureDefinition.Id(requireNotNull(instance.getTag(INSTANCE_DEF_ID_TAG)) { "Instance $instance (uuid ${instance.uniqueId}) has no InstanceDefinition." })
        return requireNotNull(getInstanceDefinition(instanceDefId)) { "Instance $instance (uuid ${instance.uniqueId}) has invalid InstanceDefinition id $instanceDefId." }
    }

    override fun onRegister(definition: InstanceDefinition<*>) {
        map[definition.id] = definition

        transaction {
            SchemaUtils.create(definition.data.table)
        }

        definition.registerDimension()
    }

    override fun onRegisterModule() {
        for (event in events) {
            eventNode.addListener(event.java) {
                val instance = getInstanceFromEvent(it)
                instance.definition.delegateEvent(it, instance)
            }
        }
    }
}

abstract class InstanceData(id: EntityID<UUID>) : FeatureData<UUID>(id) {
    abstract class Class<DATA : InstanceData>(
        table: IdTable<UUID>,
    ) : FeatureData.Class<UUID, DATA>(table)
}

abstract class InstanceDefinition<DATA : InstanceData>(
    data: InstanceData.Class<DATA>,
) : FeatureDefinition<UUID, DATA>(data) {
    open val chunkLoader: IChunkLoader? = null
    open val dimensionType: DimensionType? = null
    open var dimensionTypeKey: DynamicRegistry.Key<DimensionType> = DimensionType.OVERWORLD
    val instanceContainers = mutableListOf<InstanceContainer>()
    open val defaultSpawnPoint: Pos = Pos.ZERO

    fun getData(instance: Instance): DATA =
        getData(instance.uniqueId)
            ?: error("Instance $instance (uuid ${instance.uniqueId}) has no associated InstanceData for InstanceDefinition $id.")

    // TODO: make this event delegation stuff generic
    internal fun delegateEvent(event: Event, instance: Instance) =
        CoroutineScope(MinestomDispatcher).launch {
            when (event) {
                is PlayerSpawnEvent -> event.handle(getData(instance))
                is PlayerStartSneakingEvent -> event.handle(getData(instance))
                is PlayerStopSneakingEvent -> event.handle(getData(instance))
                is PlayerMoveEvent -> event.handle(getData(instance))

                else -> error("No event handler for event ${event::class}.")
            }
        }

    open suspend fun PlayerSpawnEvent.handle(data: DATA) {}
    open suspend fun PlayerStartSneakingEvent.handle(data: DATA) {}
    open suspend fun PlayerStopSneakingEvent.handle(data: DATA) {}
    open suspend fun PlayerMoveEvent.handle(data: DATA) {}

    open suspend fun onTick() {}
    open fun onCreateInstanceContainer(instanceContainer: InstanceContainer): InstanceContainer = instanceContainer

    internal fun registerDimension() {
        val dimensionType = dimensionType
        if (dimensionType != null) {
            dimensionTypeKey = DimensionTypeRegistry.register(id.value.lowercase(), dimensionType)
        }
    }

    @Suppress("UnstableApiUsage")
    fun createInstanceContainer(): InstanceContainer =
        transaction {
            var instanceContainer = InstanceManager.createInstanceContainer(dimensionTypeKey, chunkLoader)

            val instanceData = data.new(instanceContainer.uniqueId) {}

            instanceContainer.setTag(INSTANCE_DEF_ID_TAG, this@InstanceDefinition.id.value)
            instanceContainer.setTag(INSTANCE_DATA_ID_TAG, instanceData.id.value)

            instanceContainer = onCreateInstanceContainer(instanceContainer)
            instanceContainers.add(instanceContainer)

            fun runEveryTick() {
                CoroutineScope(MinestomDispatcher).launch {
                    onTick()
                }
                SchedulerManager.scheduleNextTick(::runEveryTick)
            }
            SchedulerManager.scheduleNextTick(::runEveryTick)

            instanceContainer
        }

    fun getFirstInstanceOrNew(): Instance =
        instanceContainers.getOrElse(0) { createInstanceContainer() }
}

val Instance.definition: InstanceDefinition<*>
    get() = Instances.getInstanceDefinition(this)