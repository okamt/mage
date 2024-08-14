package helio.module.integration

import helio.module.*
import helio.util.exposed.pos
import helio.util.listenWith
import net.bladehunt.kotstom.InstanceManager
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import java.util.*

/**
 * The player instances module.
 *
 * Handles storage of the player's position and instance.
 */
@BuiltinModule(BuiltinModuleType.INTEGRATION)
object PlayerInstances : ServerModule("playerInstances") {
    lateinit var defaultInstance: InstanceDefinition<*>

    override fun onRegisterModule() {
        check(::defaultInstance.isInitialized) { "defaultInstance must be set." }

        eventNode.listenWith<AsyncPlayerConfigurationEvent> {
            val playerInstanceData = PlayerInstance.getData(player.uuid)
            if (playerInstanceData != null) {
                val instance = InstanceManager.getInstance(playerInstanceData.instanceUUID) ?: return@listenWith
                spawningInstance = instance
                player.respawnPoint = playerInstanceData.pos.value
            } else {
                spawningInstance = defaultInstance.getFirstInstanceOrNew()
                player.respawnPoint = defaultInstance.defaultSpawnPoint
            }
        }

        eventNode.listenWith<PlayerDisconnectEvent> {
            PlayerInstance.writeData(player.uuid) {
                instanceUUID = player.instance.uniqueId
                pos.value = player.position
            }
        }
    }
}

@RegisterFeature(Players::class)
object PlayerInstance : PlayerDataDefinition<PlayerInstance.Data>(Data) {
    const val ID = "playerInstance"
    override val id = Id(ID)

    class Data(id: EntityID<UUID>) : PlayerData(id) {
        companion object : Class<Data>(Table)

        object Table : UUIDTable(ID) {
            val instanceUUID = uuid("instanceUUID")
            val pos = pos("pos")
        }

        var instanceUUID by Table.instanceUUID
        val pos by Table.pos
    }
}
