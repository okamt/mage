package helio.module.integration

import helio.module.*
import helio.util.listen
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

const val PLAYER_INVENTORY_SIZE = 46

/**
 * The player inventories module.
 *
 * Handles storage of the player's inventory.
 */
@BuiltinModule(BuiltinModuleType.INTEGRATION)
object PlayerInventories : ServerModule("playerInventories") {
    override fun onRegisterModule() {
        eventNode.listen<PlayerSpawnEvent> {
            if (isFirstSpawn) {
                player.loadInventory()
            }
        }

        eventNode.listen<PlayerDisconnectEvent> {
            player.saveInventory()
        }
    }
}

fun Player.loadInventory() {
    assert(inventory.itemStacks.size == PLAYER_INVENTORY_SIZE)
    transaction {
        val data = PlayerInventory.getData(uuid) ?: return@transaction
        data.itemDefIds.zip(data.itemDataIds).withIndex().forEach {
            val (itemDefId, itemDataId) = it.value
            if (itemDefId == null) {
                return@forEach
            }
            val itemRepr = ItemRepr(FeatureDefinition.Id(itemDefId), itemDataId)
            inventory.setItemStack(it.index, itemRepr.createItemStack())
        }
    }
}

fun Player.saveInventory() {
    assert(inventory.itemStacks.size == PLAYER_INVENTORY_SIZE)
    transaction {
        val itemReprs = inventory.itemStacks.map { if (it.isAir) null else it.itemRepr }
        PlayerInventory.writeData(uuid) {
            itemDefIds = itemReprs.map { it?.itemDefId?.value }
            itemDataIds = itemReprs.map { it?.itemDataId ?: 0 }
        }
    }
}

@RegisterFeature(Players::class)
object PlayerInventory : PlayerDataDefinition<PlayerInventory.Data>(Data) {
    const val ID = "playerInventory"
    override val id = Id(ID)

    class Data(id: EntityID<UUID>) : PlayerData(id) {
        companion object : Class<Data>(Table)

        object Table : UUIDTable(ID) {
            val itemDefIds =
                array<String?>("itemDefIds", VarCharColumnType(colLength = Id.MAX_LEN), PLAYER_INVENTORY_SIZE)
            val itemDataIds = array<Int>("itemDataIds", PLAYER_INVENTORY_SIZE)
        }

        var itemDefIds by Table.itemDefIds
        var itemDataIds by Table.itemDataIds
    }
}
