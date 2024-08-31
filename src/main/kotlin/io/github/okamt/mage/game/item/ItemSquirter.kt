package io.github.okamt.mage.game.item

import io.github.okamt.mage.PersistentData
import io.github.okamt.mage.game.item.ItemSquirter.Data
import io.github.okamt.mage.module.ItemDataId
import io.github.okamt.mage.module.ItemDefinition
import io.github.okamt.mage.module.ItemRegistry
import io.github.okamt.mage.module.RegisterFeature
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
