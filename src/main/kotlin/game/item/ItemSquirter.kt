package helio.game.item

import helio.module.ItemDefinitionWithoutData
import helio.module.ItemRegistry
import helio.module.RegisterFeature
import net.minestom.server.event.item.ItemUpdateStateEvent
import net.minestom.server.event.player.PlayerItemAnimationEvent
import net.minestom.server.item.Material

@RegisterFeature(ItemRegistry::class)
object ItemSquirter : ItemDefinitionWithoutData() {
    override val id = Id("squirter")
    override val material: Material = Material.SHIELD

    override val events = events {
        handle<PlayerItemAnimationEvent> {
            player.sendMessage("START")
        }

        handle<ItemUpdateStateEvent> {
            player.sendMessage("STOP")
        }
    }
}
