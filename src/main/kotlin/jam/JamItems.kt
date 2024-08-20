package helio.jam

import helio.jam.JamGame.breakBlocksAroundPoint
import helio.module.*
import helio.util.listenWith
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
import kotlin.math.roundToInt

fun getRandomItem(): ItemStack =
    weightedRandom(
        1.0 to AnvilItem,
        1.0 to BallItem,
        1.0 to TNTItem,
        1.0 to DashItem,
    ).createItemStack()

@RegisterFeature(ItemRegistry::class)
object AnvilItem : ItemDefinitionWithoutData() {
    override val id = Id("Anvil")
    override val material: Material = Material.ANVIL

    override val events = events {
        handleAsync<PlayerUseItemEvent> {
            if (player.data.isUsingItem) {
                return@handleAsync
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
                    instance.playSound(
                        Sound.sound(
                            SoundEvent.ENTITY_GENERIC_EXPLODE,
                            Sound.Source.AMBIENT,
                            1f,
                            1f
                        ),
                        player.position
                    )
                }
                anchor.velocity = anchor.velocity.withY(if (player.data.pink) -100.0 else -50.0)
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
}

@RegisterFeature(ItemRegistry::class)
object BallItem : ItemDefinitionWithoutData() {
    override val id = Id("Big Ball")
    override val material: Material = Material.SNOWBALL

    override val events = events {
        handleAsync<PlayerUseItemEvent> {
            player.data.isUsingItem = true
            player.inventory.clear()

            player.playSound(
                Sound.sound(SoundEvent.ENTITY_EGG_THROW, Sound.Source.AMBIENT, 1f, 1f),
                Sound.Emitter.self()
            )

            val ball = EntityProjectile(player, EntityType.SNOWBALL)
            ball.setNoGravity(true)
            ball.velocity = (player.position.direction() * 50.0).asVec()
            ball.isInvisible = true
            ball.setInstance(instance, player.position + player.position.direction()).await()

            val ball2 = Entity(EntityType.ITEM_DISPLAY)
            val meta = (ball2.entityMeta as ItemDisplayMeta)
            meta.itemStack = ItemStack.of(Material.SNOWBALL)
            meta.scale = (Vec.ONE * (if (player.data.pink) 20.0 else 10.0)).asVec()
            meta.billboardRenderConstraints = AbstractDisplayMeta.BillboardConstraints.CENTER
            ball2.setNoGravity(true)
            ball2.setInstance(instance, player.position + player.position.direction()).await()

            ball.addPassenger(ball2)

            player.data.isUsingItem = false

            fun explode() {
                val radius = if (player.data.pink) 10 else 5

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

                SchedulerManager.scheduleNextTick {
                    ball2.remove()
                    ball.remove()
                }
            }

            ball.eventNode().listenWith<ProjectileCollideWithBlockEvent> {
                explode()
            }

            val dir = (player.position.direction() * 50.0).asVec()

            fun updateBall() {
                if (!ball.isRemoved) {
                    ball.velocity = dir
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
}

@RegisterFeature(ItemRegistry::class)
object TNTItem : ItemDefinitionWithoutData() {
    override val id = Id("Mega TNT")
    override val material: Material = Material.TNT

    override val events = events {
        handleAsync<PlayerUseItemEvent> {
            player.data.isUsingItem = true
            player.inventory.clear()

            val tnt = EntityProjectile(player, EntityType.SNOWBALL)
            tnt.velocity = tnt.velocity.withY(player.velocity.y * 1.5)
            tnt.isInvisible = true

            val actualTnt = Entity(EntityType.FALLING_BLOCK)
            (actualTnt.entityMeta as FallingBlockMeta).block = Block.TNT

            actualTnt.setInstance(instance, player.position).await()
            tnt.setInstance(instance, player.position).await()
            tnt.addPassenger(actualTnt)

            fun explode() {
                breakBlocksAroundPoint(tnt.position, if (player.data.pink) 10.0 else 5.0, tnt = true) {
                    instance.sendGroupedPacket(particle {
                        particle = Particle.EXPLOSION
                        position = Vec(x.toDouble(), y.toDouble(), z.toDouble())
                        count = 1
                    })
                }
                instance.playSound(
                    Sound.sound(
                        SoundEvent.ENTITY_GENERIC_EXPLODE,
                        Sound.Source.AMBIENT,
                        1f,
                        1f
                    ),
                    tnt.position
                )

                SchedulerManager.scheduleNextTick {
                    actualTnt.remove()
                    tnt.remove()
                }
            }

            tnt.eventNode().listenWith<ProjectileCollideWithBlockEvent> {
                explode()
            }

            player.data.isUsingItem = false

            delay(3000)

            if (!tnt.isRemoved) {
                explode()
            }
        }
    }
}

@RegisterFeature(ItemRegistry::class)
object DashItem : ItemDefinitionWithoutData() {
    override val id = Id("Dash")
    override val material: Material = Material.FEATHER

    override val events = events {
        handleAsync<PlayerUseItemEvent> {
            player.data.isUsingItem = true
            player.inventory.clear()

            var ticks = (20 * 1.0).roundToInt()
            val velocity = if (player.data.pink) 125.0 else 75.0

            fun dash() {
                if (ticks <= 0) {
                    return
                } else if (ticks % 2 == 0) {
                    player.playSound(
                        Sound.sound(
                            SoundEvent.BLOCK_NOTE_BLOCK_HAT,
                            Sound.Source.AMBIENT,
                            1f,
                            1f,
                        )
                    )
                }

                player.velocity = (player.position.direction() * velocity).asVec()

                ticks -= 1

                SchedulerManager.scheduleNextTick(::dash)
            }

            SchedulerManager.scheduleNextTick(::dash)

            player.data.isUsingItem = false
        }
    }
}