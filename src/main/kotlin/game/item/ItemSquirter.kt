package helio.game.item

import helio.game.item.ItemSquirter.Data
import helio.module.ItemDataId
import helio.module.ItemDefinition
import helio.module.ItemRegistry
import helio.module.PersistentData
import helio.module.RegisterFeature
import net.minestom.server.event.item.ItemUpdateStateEvent
import net.minestom.server.event.player.PlayerItemAnimationEvent
import net.minestom.server.item.Material
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

@RegisterFeature(ItemRegistry::class)
object ItemSquirter : ItemDefinition<Data>(Data) {
    const val ID = "squirter"
    override val id = Id(ID)
    override val material: Material = Material.SHIELD

    class Data(id: EntityID<ItemDataId>) : PersistentData<ItemDataId>(id) {
        companion object : Store<ItemDataId, Data>(Table)

        object Table : IntIdTable(ID) {
            val squirted = integer("squirted").default(0)
        }

        var squirted by Table.squirted
    }

    fun PlayerItemAnimationEvent.handle(data: Data) {
        data.squirted += 1
        player.sendMessage("squirted ${data.squirted} times")
    }

    fun ItemUpdateStateEvent.handle() {
        player.sendMessage("stopped.")
    }
}
