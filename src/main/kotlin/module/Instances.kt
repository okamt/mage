package helio.module

import net.bladehunt.kotstom.DimensionTypeRegistry
import net.bladehunt.kotstom.InstanceManager
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.IChunkLoader
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.registry.DynamicRegistry
import net.minestom.server.world.DimensionType
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/**
 * The instances module.
 */
object Instances : ServerModule("instances"), FeatureRegistry<InstanceDefinition<*>> {
    private val map: MutableMap<String, InstanceDefinition<*>> = mutableMapOf()

    override fun register(definition: InstanceDefinition<*>) {
        map[definition.id] = definition

        transaction {
            SchemaUtils.create(definition.data.table)
        }

        definition.registerDimension()
    }

    override fun onRegisterModule() {}
}

abstract class InstanceData(id: EntityID<UUID>) : FeatureData<UUID>(id) {
    abstract class Class<DATA : InstanceData>(
        table: IdTable<UUID>,
    ) : FeatureData.Class<UUID, DATA>(table)
}

abstract class InstanceDefinition<DATA : InstanceData>(
    dataClass: InstanceData.Class<DATA>,
) : FeatureDefinition<UUID, DATA>(dataClass) {
    open val chunkLoader: IChunkLoader? = null
    open val dimensionType: DimensionType? = null
    open var dimensionTypeKey: DynamicRegistry.Key<DimensionType> = DimensionType.OVERWORLD
    val instanceContainers = mutableListOf<InstanceContainer>()
    open val defaultSpawnPoint: Pos = Pos.ZERO

    open fun onCreateInstanceContainer(instanceContainer: InstanceContainer): InstanceContainer = instanceContainer

    internal fun registerDimension() {
        val dimensionType = dimensionType
        if (dimensionType != null) {
            dimensionTypeKey = DimensionTypeRegistry.register(id.lowercase(), dimensionType)
        }
    }

    @Suppress("UnstableApiUsage")
    fun createInstanceContainer(): InstanceContainer {
        var instanceContainer = InstanceManager.createInstanceContainer(dimensionTypeKey, chunkLoader)

        instanceContainer = onCreateInstanceContainer(instanceContainer)
        instanceContainers.add(instanceContainer)

        return instanceContainer
    }

    fun getFirstInstanceOrNew(): Instance =
        instanceContainers.getOrElse(0) { createInstanceContainer() }
}
