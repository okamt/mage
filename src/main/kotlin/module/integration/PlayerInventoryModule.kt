package helio.module.integration

import helio.module.*
import helio.util.listenWith
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.VarCharColumnType
import java.util.*

const val PLAYER_INVENTORY_SIZE = 46

/**
 * The player inventories module.
 *
 * Handles storage of the player's inventory using [PersistentData].
 */
@BuiltinModule(BuiltinModuleType.INTEGRATION)
object PlayerInventoryModule : ServerModule("playerInventoryModule") {
    override fun onRegisterModule() {
        eventNode.listenWith<PlayerSpawnEvent> {
            if (isFirstSpawn) {
                player.loadInventory()
            }
        }

        eventNode.listenWith<PlayerDisconnectEvent> {
            player.saveInventory()
        }
    }
}

fun Player.loadInventory() {
    assert(inventory.itemStacks.size == PLAYER_INVENTORY_SIZE)

    PlayerInventoryFeature.withData(uuid) {
        for (pair in itemDefIds.zip(itemDataIds).withIndex()) {
            val (itemDefId, itemDataId) = pair.value
            if (itemDefId == null) {
                continue
            }
            val itemRepr = ItemRepr(FeatureDefinition.Id(itemDefId), itemDataId)
            inventory.setItemStack(pair.index, itemRepr.createItemStack())
        }
    }
}

fun Player.saveInventory() {
    assert(inventory.itemStacks.size == PLAYER_INVENTORY_SIZE)

    val itemReprs = inventory.itemStacks.map { if (it.isAir) null else it.repr }
    PlayerInventoryFeature.writeData(uuid) {
        itemDefIds = itemReprs.map { it?.defId?.value }
        itemDataIds = itemReprs.map { it?.dataId ?: 0 }
    }
}

@RegisterFeature(PlayerFeatureRegistry::class)
object PlayerInventoryFeature : FeatureDefinition(), DataStore<PlayerDataId, PlayerInventoryFeature.Data> by Data {
    const val ID = "playerInventory"
    override val id = Id(ID)

    class Data(id: EntityID<UUID>) : PersistentData<UUID>(id) {
        companion object : Store<UUID, Data>(Table)

        object Table : UUIDTable(ID) {
            val itemDefIds =
                array<String?>("itemDefIds", VarCharColumnType(colLength = Id.MAX_LEN), PLAYER_INVENTORY_SIZE)
            val itemDataIds = array<Int>("itemDataIds", PLAYER_INVENTORY_SIZE)
        }

        var itemDefIds by Table.itemDefIds
        var itemDataIds by Table.itemDataIds
    }
}
