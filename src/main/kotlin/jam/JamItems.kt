package helio.jam

import helio.jam.JamGame.breakBlocksAroundPoint
import helio.module.ItemData
import helio.module.ItemDefinition
import helio.module.Items
import helio.module.RegisterFeature
import net.bladehunt.kotstom.SchedulerManager
import net.bladehunt.kotstom.dsl.particle
import net.kyori.adventure.sound.Sound
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.other.FallingBlockMeta
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.particle.Particle
import net.minestom.server.sound.SoundEvent

fun getRandomItem(): ItemStack =
    weightedRandom(
        1.0 to AnvilItem
    ).createItemStack()

@RegisterFeature(Items::class)
object AnvilItem : ItemDefinition<ItemData.Empty>(ItemData.Empty) {
    const val ID = "anvil"
    override val id = Id(ID)
    override val material: Material = Material.ANVIL

    override fun PlayerUseItemEvent.handle(data: ItemData.Empty) {
        if (player.data.isUsingItem) {
            return
        }

        val anchor = Entity(EntityType.ARMOR_STAND)
        anchor.isInvisible = true

        val anvil = Entity(EntityType.FALLING_BLOCK)
        val meta = anvil.entityMeta as FallingBlockMeta
        meta.block = Block.ANVIL

        anchor.setInstance(player.instance, player.position)
        anvil.setInstance(player.instance)
        anchor.addPassenger(anvil)
        anchor.addPassenger(player)

        var timer = 20 * 5

        fun use() {
            if (anchor.isOnGround) {
                breakBlocksAroundPoint(anchor.position) {
                    player.sendPacket(particle {
                        particle = Particle.EXPLOSION
                        position = Vec(x.toDouble(), y.toDouble(), z.toDouble())
                        count = 1
                    })
                }
                player.playSound(
                    Sound.sound(
                        SoundEvent.ENTITY_GENERIC_EXPLODE,
                        Sound.Source.AMBIENT,
                        1f,
                        1f
                    )
                )
            }
            anchor.velocity = anchor.velocity.withY(-50.0)
            timer -= 1
            if (timer < 0) {
                anchor.remove()
                anvil.remove()
                player.velocity = player.velocity.withY(-10.0)
                player.data.isUsingItem = false
            } else {
                SchedulerManager.scheduleNextTick(::use)
            }
        }

        SchedulerManager.scheduleNextTick(::use)

        player.inventory.clear()
        player.data.isUsingItem = true
    }
}