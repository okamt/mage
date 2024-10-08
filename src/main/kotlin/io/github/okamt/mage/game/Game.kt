package io.github.okamt.mage.game

import io.github.okamt.mage.game.item.DefaultInstance
import io.github.okamt.mage.game.item.ItemSquirter
import io.github.okamt.mage.module.integration.PlayerInstanceModule
import io.github.okamt.mage.module.registerAllAnnotatedFeatures
import io.github.okamt.mage.module.registerAllBuiltinModules
import io.github.okamt.mage.util.listenWith
import net.bladehunt.kotstom.GlobalEventHandler
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.extras.MojangAuth
import org.jetbrains.exposed.sql.Database

private object Game

val db = Database.connect(config.databaseURL, driver = config.databaseDriver)

fun start() {
    val minecraftServer = MinecraftServer.init()

    MojangAuth.init()

    PlayerInstanceModule.defaultInstance = DefaultInstance

    registerAllBuiltinModules()
    registerAllAnnotatedFeatures(Game.javaClass.packageName)

    val before = EventNode.all("before")
    before.priority = -10

    val after = EventNode.all("after")
    after.priority = 10

    after.listenWith<PlayerSpawnEvent> {
        if (!isFirstSpawn) return@listenWith
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

    GlobalEventHandler.addChild(before)
    GlobalEventHandler.addChild(after)

    minecraftServer.start(config.address, config.port)
}
