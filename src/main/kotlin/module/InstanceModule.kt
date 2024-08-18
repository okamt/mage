package helio.module

import net.bladehunt.kotstom.DimensionTypeRegistry
import net.bladehunt.kotstom.InstanceManager
import net.bladehunt.kotstom.dsl.scheduleTask
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.trait.EntityEvent
import net.minestom.server.event.trait.InstanceEvent
import net.minestom.server.event.trait.PlayerEvent
import net.minestom.server.instance.IChunkLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.registry.DynamicRegistry
import net.minestom.server.tag.Tag
import net.minestom.server.timer.TaskSchedule
import net.minestom.server.world.DimensionType
import java.util.*

val INSTANCE_DEF_ID_TAG = Tag.String("instanceId")
val INSTANCE_DATA_ID_TAG = Tag.UUID("instanceDataId")

/**
 * The instances module.
 */
@BuiltinModule(BuiltinModuleType.CORE)
object InstanceModule : ServerModule("instanceModule") {
    override fun onRegisterModule() {}
}

object InstanceRegistry : MapFeatureRegistry<InstanceDefinition<*>>() {
    override fun onRegister(definition: InstanceDefinition<*>) {
        super.onRegister(definition)

        definition.registerDimension()
    }

    fun getDefinition(instance: Instance): InstanceDefinition<*> {
        val instanceDefId =
            FeatureDefinition.Id(requireNotNull(instance.getTag(INSTANCE_DEF_ID_TAG)) { "Instance $instance (uuid ${instance.uniqueId}) has no InstanceDefinition." })
        return requireNotNull(getDefinition(instanceDefId)) { "Instance $instance (uuid ${instance.uniqueId}) has invalid InstanceDefinition id $instanceDefId." }
    }
}

typealias InstanceDataId = UUID

abstract class InstanceDefinitionWithoutData : InstanceDefinition<Unit>(DummyDataStore(UUID(0, 0)))

abstract class InstanceDefinition<DATA>(
    dataStore: DataStore<InstanceDataId, DATA>,
) : FeatureDefinition(), DataStore<InstanceDataId, DATA> by dataStore {
    open val chunkLoader: IChunkLoader? = null
    open val dimensionType: DimensionType? = null
    open var dimensionTypeKey: DynamicRegistry.Key<DimensionType> = DimensionType.OVERWORLD
    private val instanceContainers = mutableListOf<InstanceContainer>()
    open val defaultSpawnPoint: Pos = Pos.ZERO

    val instances
        get() = listOf(instanceContainers)

    open val events by lazy { events {} }

    protected fun events(block: MultiEventHandler<InstanceDataId>.() -> Unit): MultiEventHandler<InstanceDataId> =
        MultiEventHandler<InstanceDataId>(id.value)
            .apply {
                dataFor<InstanceEvent> { instance.uniqueId }
                dataFor<PlayerEvent> { player.instance.uniqueId }
                dataFor<EntityEvent> { entity.instance.uniqueId }
            }
            .apply(block)

    override fun onRegisterDefinition() {
        ItemModule.eventNode.addChild(events.eventNode)
    }

    open val onTick: (() -> TaskSchedule)? = null
    open fun onCreateInstanceContainer(instanceContainer: InstanceContainer): InstanceContainer = instanceContainer

    internal fun registerDimension() {
        val dimensionType = dimensionType
        if (dimensionType != null) {
            dimensionTypeKey = DimensionTypeRegistry.register(id.value.lowercase(), dimensionType)
        }
    }

    @Suppress("UnstableApiUsage")
    fun createInstanceContainer(): InstanceContainer {
        var instanceContainer = InstanceManager.createInstanceContainer(dimensionTypeKey, chunkLoader)

        writeData(instanceContainer.uniqueId) {}

        instanceContainer.setTag(INSTANCE_DEF_ID_TAG, this@InstanceDefinition.id.value)
        instanceContainer.setTag(INSTANCE_DATA_ID_TAG, instanceContainer.uniqueId)

        instanceContainer = onCreateInstanceContainer(instanceContainer)
        instanceContainers.add(instanceContainer)

        val onTick = onTick
        if (onTick != null) {
            instanceContainer.scheduleTask {
                onTick.invoke()
            }
        }

        return instanceContainer
    }

    fun getFirstInstanceOrNew(): Instance =
        instanceContainers.getOrElse(0) { createInstanceContainer() }
}

val Instance.definition: InstanceDefinition<*>
    get() = InstanceRegistry.getDefinition(this)
