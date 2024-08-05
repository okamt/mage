package helio.game.item

import helio.module.ItemData
import helio.module.ItemDefinition
import helio.module.Items
import helio.module.RegisterFeature
import net.bladehunt.kotstom.extension.adventure.asMini
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.item.Material
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.transactions.transaction

@RegisterFeature(Items::class)
object ItemSquirter : ItemDefinition<ItemSquirter.Data>(Data) {
    const val ID = "squirter"
    override val id = Id(ID)
    override val material: Material = Material.REPEATER

    class Data(id: EntityID<Int>) : ItemData(id) {
        companion object : Class<Data>(Table)

        object Table : IntIdTable(ID) {
            val clicked = integer("clicked").default(0)
        }

        var clicked by Table.clicked
    }

    override fun PlayerUseItemEvent.handle(data: Data) {
        transaction {
            data.clicked += 1
        }
        player.sendMessage(
            "<rainbow>I've squirted ${data.clicked} times<rainbow>".asMini()
        )
    }
}
