package helio.module

import net.bladehunt.kotstom.ConnectionManager
import net.minestom.server.entity.Player
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
@BuiltinModule(BuiltinModuleType.CORE)
object Players : ServerModule("players"), FeatureRegistry<PlayerDataDefinition<*>> {
    private val map: MutableMap<FeatureDefinition.Id, PlayerDataDefinition<*>> = mutableMapOf()

    override fun onRegister(definition: PlayerDataDefinition<*>) {
        map[definition.id] = definition

        transaction {
            SchemaUtils.create(definition.data.table)
        }
    }

    fun getPlayerDataDefinition(id: FeatureDefinition.Id): PlayerDataDefinition<*>? {
        return map[id]
    }

    override fun onRegisterModule() {
        ConnectionManager.setPlayerProvider(::PlayerWrapper)
    }
}

class PlayerWrapper(
    uuid: UUID,
    username: String,
    playerConnection: PlayerConnection,
) : Player(uuid, username, playerConnection) {
}

val Player.wrapper
    get() = this as PlayerWrapper

abstract class PlayerData(id: EntityID<UUID>) : FeatureData<UUID>(id) {
    abstract class Class<DATA : PlayerData>(table: IdTable<UUID>) : FeatureData.Class<UUID, DATA>(table)
}

abstract class PlayerDataDefinition<DATA : PlayerData>(
    dataClass: PlayerData.Class<DATA>,
) : FeatureDefinition<UUID, DATA>(dataClass) {
    fun getDataOrNew(player: Player, init: DATA.() -> Unit): DATA =
        getDataOrNew(player.uuid, init)
}
