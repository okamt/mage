package io.github.okamt.mage.module.integration

import io.github.okamt.mage.DataStore
import io.github.okamt.mage.PersistentData
import io.github.okamt.mage.module.*
import io.github.okamt.mage.util.exposed.pos
import io.github.okamt.mage.util.listenWith
import io.github.okamt.mage.util.orRun
import net.bladehunt.kotstom.InstanceManager
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import java.util.*

/**
 * The player instances module.
 *
 * Handles storage of the player's position and instance using [PersistentData].
 */
@Builtin(BuiltinType.INTEGRATION)
object PlayerInstanceModule : ServerModule("playerInstanceModule") {
    lateinit var defaultInstance: InstanceDefinition<*>

    override fun onRegisterModule() {
        check(::defaultInstance.isInitialized) { "defaultInstance must be set." }

        eventNode.listenWith<AsyncPlayerConfigurationEvent> {
            PlayerInstanceFeature.withData(player.uuid) {
                spawningInstance = InstanceManager.getInstance(instanceUUID) ?: return@withData
                player.respawnPoint = pos.value
            } orRun {
                spawningInstance = defaultInstance.getFirstInstanceOrNew()
                player.respawnPoint = defaultInstance.defaultSpawnPoint
            }
        }

        eventNode.listenWith<PlayerDisconnectEvent> {
            PlayerInstanceFeature.writeData(player.uuid) {
                instanceUUID = player.instance.uniqueId
                pos.value = player.position
            }
        }
    }
}

@Builtin(BuiltinType.INTEGRATION)
@RegisterFeature(PlayerFeatureRegistry::class)
object PlayerInstanceFeature : FeatureDefinition(), DataStore<PlayerDataId, PlayerInstanceFeature.Data> by Data {
    const val ID = "playerInstance"
    override val id = Id(ID)

    class Data(id: EntityID<UUID>) : PersistentData<UUID>(id) {
        companion object : Store<UUID, Data>(Table)

        object Table : UUIDTable(ID) {
            val instanceUUID = uuid("instanceUUID")
            val pos = pos("pos")
        }

        var instanceUUID by Table.instanceUUID
        val pos by Table.pos
    }
}
