package helio.game.instance

import helio.module.*
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.world.DimensionType

@RegisterFeature(InstanceRegistry::class)
object DefaultInstance : InstanceDefinitionWithoutData() {
    override val id = Id("defaultInstance")

    override val dimensionType = DimensionType.builder().ambientLight(1f).build()
    override val defaultSpawnPoint = Pos(0.0, 42.0, 0.0)

    override fun onCreateInstanceContainer(instanceContainer: InstanceContainer): InstanceContainer {
        instanceContainer.setGenerator { unit ->
            unit.modifier().fillHeight(0, 40, Block.STONE)
        }
        return instanceContainer
    }
}