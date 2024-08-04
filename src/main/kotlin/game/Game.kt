package helio.game

import helio.game.instance.DefaultInstance
import helio.game.item.ItemSquirter
import helio.module.integrations.PlayerInstances
import helio.module.registerAllAnnotatedFeatures
import helio.module.registerAllBuiltinModules
import helio.util.addListener
import net.bladehunt.kotstom.GlobalEventHandler
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.extras.MojangAuth
import org.jetbrains.exposed.sql.Database

private object Game

val db = Database.connect(config.databaseURL, driver = config.databaseDriver)

fun start() {
    val minecraftServer = MinecraftServer.init()

    MojangAuth.init()

    GlobalEventHandler.addListener(PlayerSpawnEvent::class) {
        player.inventory.addItemStack(ItemSquirter.createItemStack())
        player.gameMode = GameMode.ADVENTURE
        /*player.inventory.addItemStack(
            ItemStack.of(Material.STICK).with(
                ItemComponent.ATTRIBUTE_MODIFIERS, AttributeList(
                    AttributeList.Modifier(
                        Attribute.PLAYER_BLOCK_BREAK_SPEED,
                        AttributeModifier("sss", -3.9, AttributeOperation.ADD_VALUE),
                        EquipmentSlotGroup.MAIN_HAND
                    ), true
                )
            )
        )*/
    }

    PlayerInstances.defaultInstance = DefaultInstance

    registerAllBuiltinModules()
    registerAllAnnotatedFeatures(Game.javaClass.packageName)

    minecraftServer.start(config.address, config.port)
}
