package helio.module.integrations

import helio.module.*
import helio.util.addListener
import helio.util.exposed.pos
import net.bladehunt.kotstom.InstanceManager
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import java.util.*

object PlayerInstances : ServerModule("playerInstances") {
    lateinit var defaultInstance: InstanceDefinition<*>

    override fun onRegisterModule() {
        check(::defaultInstance.isInitialized) { "defaultInstance must be set." }

        eventNode.addListener(AsyncPlayerConfigurationEvent::class) {
            val playerInstanceData = PlayerInstance.getData(player.uuid)
            if (playerInstanceData != null) {
                val instance = InstanceManager.getInstance(playerInstanceData.instanceUUID) ?: return@addListener
                spawningInstance = instance
                player.respawnPoint = playerInstanceData.pos.value
            } else {
                spawningInstance = defaultInstance.getFirstInstanceOrNew()
                player.respawnPoint = defaultInstance.defaultSpawnPoint
            }
        }

        eventNode.addListener(PlayerDisconnectEvent::class) {
            PlayerInstance.writeData(player.uuid) {
                instanceUUID = player.instance.uniqueId
                pos.value = player.position
            }
        }
    }
}

@RegisterFeature(Players::class)
object PlayerInstance : PlayerDataDefinition<PlayerInstance.Data>(Data) {
    const val ID = "instance"
    override val id = ID

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
