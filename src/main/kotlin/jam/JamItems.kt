package helio.jam

import helio.jam.JamGame.breakBlocksAroundPoint
import helio.module.ItemData
import helio.module.ItemDefinition
import helio.module.Items
import helio.module.RegisterFeature
import helio.util.listen
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import net.bladehunt.kotstom.SchedulerManager
import net.bladehunt.kotstom.dsl.particle
import net.bladehunt.kotstom.extension.asVec
import net.bladehunt.kotstom.extension.plus
import net.bladehunt.kotstom.extension.times
import net.kyori.adventure.sound.Sound
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityProjectile
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta
import net.minestom.server.entity.metadata.display.ItemDisplayMeta
import net.minestom.server.entity.metadata.other.FallingBlockMeta
import net.minestom.server.event.entity.projectile.ProjectileCollideWithBlockEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.particle.Particle
import net.minestom.server.sound.SoundEvent

fun getRandomItem(): ItemStack =
    weightedRandom(
        1.0 to AnvilItem,
        1.0 to BallItem
    ).createItemStack()

@RegisterFeature(Items::class)
object AnvilItem : ItemDefinition<ItemData.Empty>(ItemData.Empty) {
    override val id = Id("Anvil")
    override val material: Material = Material.ANVIL

    override suspend fun PlayerUseItemEvent.handle(data: ItemData.Empty) {
        if (player.data.isUsingItem) {
            return
        }

        val anchor = Entity(EntityType.ARMOR_STAND)
        anchor.isInvisible = true

        val anvil = Entity(EntityType.FALLING_BLOCK)
        val meta = anvil.entityMeta as FallingBlockMeta
        meta.block = Block.ANVIL

        anchor.setInstance(player.instance, player.position).await()
        anvil.setInstance(player.instance, player.position).await()
        anchor.addPassenger(anvil)

        anvil.addPassenger(player)

        var timer = 20 * 5

        fun use() {
            if (anchor.isOnGround) {
                breakBlocksAroundPoint(anchor.position) {
                    instance.sendGroupedPacket(particle {
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
                if (!anvil.isRemoved) {
                    anvil.removePassenger(player)
                    anvil.remove()
                    player.velocity = player.velocity.withY(-10.0)
                    player.data.isUsingItem = false
                }
            } else {
                SchedulerManager.scheduleNextTick(::use)
            }
        }

        SchedulerManager.scheduleNextTick(::use)

        player.inventory.clear()
        player.data.isUsingItem = true
    }
}

@RegisterFeature(Items::class)
object BallItem : ItemDefinition<ItemData.Empty>(ItemData.Empty) {
    override val id = Id("Big Ball")
    override val material: Material = Material.SNOWBALL

    override suspend fun PlayerUseItemEvent.handle(data: ItemData.Empty) {
        player.data.isUsingItem = true
        player.inventory.clear()

        player.playSound(Sound.sound(SoundEvent.ENTITY_EGG_THROW, Sound.Source.AMBIENT, 1f, 1f), Sound.Emitter.self())

        val ball = EntityProjectile(player, EntityType.SNOWBALL)
        ball.setNoGravity(true)
        ball.velocity = (player.position.direction() * 50.0).asVec()
        ball.isInvisible = true
        ball.setInstance(instance, player.position + player.position.direction()).await()

        val ball2 = Entity(EntityType.ITEM_DISPLAY)
        val meta = (ball2.entityMeta as ItemDisplayMeta)
        meta.itemStack = ItemStack.of(Material.SNOWBALL)
        meta.scale = (Vec.ONE * 10.0).asVec()
        meta.billboardRenderConstraints = AbstractDisplayMeta.BillboardConstraints.CENTER
        ball2.setNoGravity(true)
        ball2.setInstance(instance, player.position + player.position.direction()).await()

        ball.addPassenger(ball2)

        player.data.isUsingItem = false

        fun explode() {
            val radius = 5

            for (x in ball.position.x.toInt() - radius..ball.position.x.toInt() + radius) {
                for (y in ball.position.y.toInt() - radius..ball.position.y.toInt() + radius) {
                    for (z in ball.position.z.toInt() - radius..ball.position.z.toInt() + radius) {
                        val vec = Vec(x.toDouble(), y.toDouble(), z.toDouble())
                        if (vec.distance(ball.position) <= radius) {
                            instance.setBlock(x, y, z, Block.WHITE_CONCRETE)
                        }
                    }
                }
            }
            ball2.remove()
            ball.remove()
        }

        ball.eventNode().listen<ProjectileCollideWithBlockEvent> {
            explode()
        }

        fun updateBall() {
            if (!ball.isRemoved) {
                ball.velocity = (player.position.direction() * 50.0).asVec()
                SchedulerManager.scheduleNextTick(::updateBall)
            }
        }

        SchedulerManager.scheduleNextTick(::updateBall)

        delay(700)

        if (!ball.isRemoved) {
            explode()
        }
    }
}