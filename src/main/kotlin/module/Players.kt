package helio.module

import net.bladehunt.kotstom.ConnectionManager
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.network.player.PlayerConnection
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/**
 * The players module.
 *
 * When registered, Minestom will use the [PlayerWrapper] class for players. Can be accessed via [Player.wrapper].
 */
object Players : ServerModule("players"), FeatureRegistry<PlayerDataDefinition<*>> {
    private val map: MutableMap<String, PlayerDataDefinition<*>> = mutableMapOf()

    override fun register(definition: PlayerDataDefinition<*>) {
        map[definition.id] = definition

        transaction {
            SchemaUtils.create(definition.data.table)
        }
    }

    fun getPlayerDataDefinition(id: String): PlayerDataDefinition<*>? {
        return map[id]
    }

    override fun onRegisterModule() {
        ConnectionManager.setPlayerProvider(::PlayerWrapper)

        eventNode.addListener(PlayerDisconnectEvent::class.java) { event ->
            event.player.wrapper.save()
        }
    }
}

class PlayerWrapper(
    uuid: UUID,
    username: String,
    playerConnection: PlayerConnection,
) : Player(uuid, username, playerConnection) {
    fun save() {
        TODO("Player data saving")
    }
}

val Player.wrapper
    get() = this as PlayerWrapper

abstract class PlayerData(id: EntityID<UUID>) : FeatureData<UUID>(id) {
    abstract class Class<DATA : PlayerData>(table: IdTable<UUID>) : FeatureData.Class<UUID, DATA>(table)
}

abstract class PlayerDataDefinition<DATA : PlayerData>(
    dataClass: PlayerData.Class<DATA>,
) : FeatureDefinition<UUID, DATA>(dataClass)
