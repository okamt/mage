package helio.game.instance

import helio.module.InstanceData
import helio.module.InstanceDefinition
import helio.module.Instances
import helio.module.RegisterFeature
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.world.DimensionType
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import java.util.*

@RegisterFeature(Instances::class)
object DefaultInstance : InstanceDefinition<DefaultInstance.Data>(Data) {
    const val ID = "defaultInstance"
    override val id = ID

    override val dimensionType = DimensionType.builder().ambientLight(1f).build()
    override val defaultSpawnPoint = Pos(0.0, 42.0, 0.0)

    override fun onCreateInstanceContainer(instanceContainer: InstanceContainer): InstanceContainer {
        instanceContainer.setGenerator { unit ->
            unit.modifier().fillHeight(0, 40, Block.STONE)
        }
        return instanceContainer
    }

    class Data(id: EntityID<UUID>) : InstanceData(id) {
        companion object : Class<Data>(Table)

        object Table : UUIDTable(ID)
    }
}