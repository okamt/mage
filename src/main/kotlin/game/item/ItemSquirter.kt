package helio.game.item

import helio.module.ItemData
import helio.module.ItemDefinition
import helio.module.Items
import helio.module.RegisterFeature
import net.minestom.server.event.item.ItemUpdateStateEvent
import net.minestom.server.event.player.PlayerItemAnimationEvent
import net.minestom.server.item.Material

@RegisterFeature(Items::class)
object ItemSquirter : ItemDefinition<ItemData.Empty>(ItemData.Empty) {
    const val ID = "squirter"
    override val id = Id(ID)
    override val material: Material = Material.SHIELD

    override fun PlayerItemAnimationEvent.handle(data: ItemData.Empty) {
        player.sendMessage("START")
    }

    override fun ItemUpdateStateEvent.handle(data: ItemData.Empty) {
        player.sendMessage("STOP")
    }
}
