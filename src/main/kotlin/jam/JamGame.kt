package helio.jam

import helio.module.*
import helio.util.listen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.bladehunt.kotstom.GlobalEventHandler
import net.bladehunt.kotstom.SchedulerManager
import net.bladehunt.kotstom.coroutines.MinestomDispatcher
import net.bladehunt.kotstom.dsl.particle
import net.bladehunt.kotstom.extension.adventure.asComponent
import net.bladehunt.kotstom.extension.adventure.color
import net.bladehunt.kotstom.extension.asVec
import net.bladehunt.kotstom.extension.minus
import net.bladehunt.kotstom.extension.times
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.*
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.entity.attribute.AttributeModifier
import net.minestom.server.entity.attribute.AttributeOperation
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.entity.metadata.other.EndCrystalMeta
import net.minestom.server.entity.metadata.other.FallingBlockMeta
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.WorldBorder
import net.minestom.server.instance.block.Block
import net.minestom.server.particle.Particle
import net.minestom.server.sound.SoundEvent
import net.minestom.server.tag.Tag
import net.minestom.server.world.DimensionType
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Database
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

@RegisterFeature(Instances::class)
object JamGame : InstanceDefinition<JamGame.Data>(Data) {
    const val ID = "gameInstance"
    override val id = Id(ID)

    const val RADIUS = 32
    const val DIMENSION_HEIGHT = 4064
    const val DIMENSION_MAX_Y = 2031
    const val DIMENSION_MIN_Y = -2032
    const val START_Y = DIMENSION_MAX_Y - 64
    const val END_Y = DIMENSION_MIN_Y + 64
    const val START_BARRIER_Y_OFFSET = 32
    const val TOTAL_SECTIONS = 4

    override val dimensionType =
        DimensionType.builder().ambientLight(1f).minY(DIMENSION_MIN_Y).height(DIMENSION_HEIGHT)
            .logicalHeight(DIMENSION_HEIGHT).effects("nether").build()
    override val defaultSpawnPoint = Pos(0.0, (START_Y + START_BARRIER_Y_OFFSET + 1).toDouble(), 0.0)

    enum class State {
        WAITING,
        ONGOING,
    }

    var state = State.WAITING
    lateinit var instance: Instance

    sealed interface Obstacle {
        companion object {
            fun random(): Obstacle = Obstacle::class.sealedSubclasses.map { it.objectInstance }.random() as Obstacle

            fun randomBlock() =
                listOf(
                    Block.LIGHT_BLUE_CONCRETE,
                    Block.MAGENTA_CONCRETE,
                    Block.YELLOW_CONCRETE,
                ).random()
        }

        fun generate(instance: Instance, y: Int)

        data object Rectangle : Obstacle {
            const val MAX_SIZE = 40
            const val OUT = 10

            override fun generate(instance: Instance, y: Int) {
                val block = randomBlock()
                val xSize = (1..MAX_SIZE).random()
                val zSize = MAX_SIZE + 1 - xSize
                val xStart = (-RADIUS - OUT..RADIUS + OUT - xSize).random()
                val zStart = (-RADIUS - OUT..RADIUS + OUT - zSize).random()
                //val x1 = (-RADIUS..RADIUS - PADDING).random()
                //val z1 = (-RADIUS..RADIUS - PADDING).random()
                //val x2 = (x1 + PADDING..RADIUS).random()
                //val z2 = (z1 + PADDING..RADIUS).random()
                for (x in (xStart..xStart + xSize))
                    for (z in (zStart..zStart + zSize))
                        instance.setBlock(x, y, z, block)
            }
        }

        data object Circle : Obstacle {
            val shapes = listOf(
                listOf(
                    " # ",
                    "# #",
                    " # ",
                ),
                listOf(
                    "  ##   ",
                    " #  #  ",
                    "#    # ",
                    "#    # ",
                    " #  #  ",
                    "  ##   ",
                    "       ",
                ),
                listOf(
                    "     ###     ",
                    "   ##   ##   ",
                    "  #       #  ",
                    " #         # ",
                    " #         # ",
                    "#           #",
                    "#           #",
                    "#           #",
                    " #         # ",
                    " #         # ",
                    "  #       #  ",
                    "   ##   ##   ",
                    "     ###     ",
                )
            )

            init {
                for (shape in shapes) {
                    val lens = shape.map { it.length }.distinct()
                    assert(lens.size == 1) // all rows are same size
                    assert(shape.size == lens[0]) // is square
                    assert(shape.size % 2 == 1) // side is odd (shape has a center)
                }
            }

            override fun generate(instance: Instance, y: Int) {
                val block = randomBlock()
                val shape = shapes.random()
                val radius = (shape.size / 2.0).toInt()
                val centerX = (-RADIUS..RADIUS).random()
                val centerZ = (-RADIUS..RADIUS).random()
                for ((shapeX, x) in shape.indices.zip(centerX - radius..centerX + radius)) {
                    for ((shapeZ, z) in shape.indices.zip(centerZ - radius..centerZ + radius)) {
                        if (shape[shapeZ][shapeX] == '#') {
                            instance.setBlock(x, y, z, block)
                        }
                    }
                }
            }
        }

        data object Grid : Obstacle {
            const val MIN_GRID_GAP = 3
            const val MAX_GRID_GAP = 10

            override fun generate(instance: Instance, y: Int) {
                val block = randomBlock()
                val gap = (MIN_GRID_GAP..MAX_GRID_GAP).random()
                for (x in -RADIUS..RADIUS step gap) {
                    for (z in -RADIUS..RADIUS) {
                        instance.setBlock(x, y, z, block)
                    }
                }
                for (z in -RADIUS..RADIUS step gap) {
                    for (x in -RADIUS..RADIUS) {
                        instance.setBlock(x, y, z, block)
                    }
                }
            }
        }

        data object Chicken : Obstacle {
            override fun generate(instance: Instance, y: Int) {
                val x = (-RADIUS..RADIUS).random()
                val z = (-RADIUS..RADIUS).random()
                val chicken = EntityCreature(EntityType.CHICKEN)
                chicken.setNoGravity(true)
                chicken.getAttribute(Attribute.GENERIC_SCALE).baseValue = 3.0
                val centerishX = (-RADIUS / 4..RADIUS / 4).random()
                val centerishZ = (-RADIUS / 4..RADIUS / 4).random()
                chicken.setInstance(
                    instance,
                    Pos(x.toDouble(), y.toDouble(), z.toDouble()).withLookAt(
                        Pos(
                            centerishX.toDouble(),
                            y.toDouble(),
                            centerishZ.toDouble()
                        )
                    )
                )

                fun checkChicken() {
                    if (instance.players.find {
                            it.position.y in (chicken.position.y - 100..chicken.position.y + 100) &&
                                    it.position.x in (chicken.position.x - RADIUS * 2..chicken.position.x + RADIUS * 2) &&
                                    it.position.z in (chicken.position.z - RADIUS * 2..chicken.position.z + RADIUS * 2)
                        } != null) {
                        chicken.velocity = chicken.position.direction().times(5.0).asVec()
                    }
                    if (chicken.aliveTicks > 20 * 60 * 5) {
                        chicken.remove()
                    } else {
                        SchedulerManager.scheduleNextTick(::checkChicken)
                    }
                }
                SchedulerManager.scheduleNextTick(::checkChicken)
            }
        }
    }

    class Data(id: EntityID<UUID>) : InstanceData(id) {
        companion object : Class<Data>(Table)

        object Table : UUIDTable(ID)
    }

    override fun onCreateInstanceContainer(instanceContainer: InstanceContainer): InstanceContainer {
        instanceContainer.worldBorder = WorldBorder((RADIUS * 2).toDouble(), 0.0, 0.0, 0, 0)
        instanceContainer.timeRate = 0

        setStartBarrier(instanceContainer)

        return instanceContainer
    }

    override suspend fun PlayerMoveEvent.handle(data: Data) {
        when (state) {
            State.ONGOING -> {
                if (isOnGround) {
                    player.teleport(player.position.sub(0.0, 0.25, 0.0))
                    player.playSound(
                        Sound.sound(
                            SoundEvent.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR,
                            Sound.Source.AMBIENT,
                            1f,
                            1f
                        )
                    )
                    player.damage(DamageType.FALL, 0f)

                    breakBlocksAroundPoint(newPosition) {
                        val block = instance.getBlock(x, y, z)
                        player.sendPacket(particle {
                            particle = Particle.BLOCK.withBlock(block)
                            position = Vec(x.toDouble(), y.toDouble(), z.toDouble())
                            count = 5
                        })
                    }
                }
            }

            State.WAITING -> {}
        }
    }

    val bossbars = mutableMapOf<UUID, BossBar>()
    var tick = 0
    val flySound = Sound.sound(SoundEvent.ITEM_ELYTRA_FLYING, Sound.Source.AMBIENT, 1f, 0.8f)
    const val FLY_SOUND_AIRBORNE_TICKS_THRESHOLD = 20 * 2
    const val FLY_SOUND_DURATION_TICKS = 20 * 10

    override suspend fun onTick() {
        fun getBossbarProgress(player: Player): Float = when (player.data.state) {
            JamPlayerData.State.PLAYING -> {
                val oneSection = (START_Y - END_Y).toFloat()
                val total = TOTAL_SECTIONS.toFloat() * oneSection
                val playerDepthInCurrentSection = oneSection - (player.position.y.toFloat() - END_Y)
                1f - ((oneSection * player.data.section + playerDepthInCurrentSection) / total)
                    .coerceIn(0f..1f)
            }

            JamPlayerData.State.SPECTATING -> {
                1f
            }
        }

        for (player in instance.players) {
            val bar = bossbars.getOrPut(player.uuid) {
                BossBar.bossBar(
                    "Height".color(TextColor.color(0x1fbdd2)),
                    getBossbarProgress(player),
                    BossBar.Color.BLUE,
                    BossBar.Overlay.PROGRESS
                ).addViewer(player)
            }
            bar.progress(getBossbarProgress(player))

            if (player.data.state == JamPlayerData.State.PLAYING && state == State.ONGOING) {
                if ((tick - player.data.lastPlayedFlySoundTick) >= FLY_SOUND_DURATION_TICKS && player.data.ticksAirborne >= FLY_SOUND_AIRBORNE_TICKS_THRESHOLD) {
                    player.data.lastPlayedFlySoundTick = tick
                    player.stopSound(flySound)
                    player.playSound(flySound)
                }
            }

            if (player.isOnGround) {
                player.data.ticksAirborne = 0
                player.data.lastPlayedFlySoundTick = -999
                player.stopSound(flySound)
            } else {
                player.data.ticksAirborne += 1
            }
        }

        tick += 1
    }

    override suspend fun PlayerSpawnEvent.handle(data: Data) = coroutineScope {
        if (isFirstSpawn) {
            when (state) {
                State.WAITING -> {
                    player.gameMode = GameMode.ADVENTURE
                    player.data.state = JamPlayerData.State.PLAYING
                    // TODO: Add logic to check if enough players
                    start()
                }

                State.ONGOING -> {
                    player.gameMode = GameMode.SPECTATOR
                    player.data.state = JamPlayerData.State.SPECTATING
                }
            }
        }
    }

    fun setStartBarrier(instance: Instance = this.instance, block: Block = Block.BARRIER) {
        for (x in -RADIUS..RADIUS) {
            for (z in -RADIUS..RADIUS) {
                instance.setBlock(x, START_Y + START_BARRIER_Y_OFFSET, z, block)
            }
        }
    }

    fun generateObstacles() {
        for (y in START_Y downTo END_Y step 5) {
            weightedRandom(
                1.0 to Obstacle.Rectangle,
                1.0 to Obstacle.Circle,
                0.1 to Obstacle.Grid,
                0.05 to Obstacle.Chicken
            ).generate(instance, y)
        }
    }

    fun generateBonus() {
        val margin = 10
        val gap = 10.0

        suspend fun giveItem(player: Player) {
            if (player.data.rollingItem) {
                return
            }
            player.data.rollingItem = true

            for (i in 0..20) {
                player.playSound(Sound.sound(SoundEvent.BLOCK_LEVER_CLICK, Sound.Source.NEUTRAL, 1f, 1f))
                delay(50)
            }
            player.playSound(
                Sound.sound(
                    SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP,
                    Sound.Source.AMBIENT,
                    1f,
                    1f
                )
            )
            player.inventory.clear()
            for (i in 0..8) {
                player.inventory.addItemStack(getRandomItem().withTag(Tag.UUID("distinct"), UUID.randomUUID()))
            }

            player.data.rollingItem = false
        }

        fun spawnCrystal(pos: Pos) {
            val crystal = Entity(EntityType.END_CRYSTAL)
            val meta = (crystal.entityMeta as EndCrystalMeta)
            meta.isShowingBottom = false
            crystal.setNoGravity(true)
            crystal.isGlowing = true
            crystal.setInstance(instance, pos)

            fun checkCrystal() {
                for (player in instance.players) {
                    if (crystal.boundingBox.intersectEntity(crystal.position, player)) {
                        if (player.data.rollingItem) {
                            continue
                        }

                        crystal.remove()
                        player.sendMessage("Wowzers!")
                        player.playSound(
                            Sound.sound(
                                SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP,
                                Sound.Source.AMBIENT,
                                1f,
                                1f
                            )
                        )
                        CoroutineScope(MinestomDispatcher).launch { giveItem(player) }
                        break
                    }
                }

                if (!crystal.isRemoved) {
                    SchedulerManager.scheduleNextTick(::checkCrystal)
                }
            }
            SchedulerManager.scheduleNextTick(::checkCrystal)
        }

        for (y in START_Y - 200 downTo END_Y step 200) {
            var x = 0
            var z = 0
            do {
                x = (-RADIUS + margin..RADIUS - margin).random()
                z = (-RADIUS + margin..RADIUS - margin).random()
            } while (!instance.getBlock(x, y, z).isAir)

            val direction = Pos(0.0, 0.0, 0.0, (-180..180).random().toFloat(), 0f)
            val rowDirection = direction.withYaw(direction.yaw + 90)
            var rowPos = Pos(x.toDouble(), y.toDouble(), z.toDouble())
            for (rowI in 0..2) {
                var colPos = rowPos
                for (colI in 0..2) {
                    spawnCrystal(colPos)
                    colPos = colPos.add(rowDirection.direction().times(gap))
                }
                rowPos = rowPos.add(direction.direction().times(gap))
            }
        }
    }

    suspend fun countdown() {
        instance.showTitle(Title.title("3".asComponent(), "".asComponent()))
        delay(1000)
        instance.showTitle(Title.title("2".asComponent(), "".asComponent()))
        delay(1000)
        instance.showTitle(Title.title("1".asComponent(), "".asComponent()))
        delay(1000)
        instance.showTitle(Title.title("Go!".asComponent(), "".asComponent()))
        start()
    }

    fun start() {
        for (player in instance.players) {
            player.noJump = true
            player.gravity = 0.08 / 2.0
        }
        setStartBarrier(block = Block.AIR)
        state = State.ONGOING
    }

    fun reset() {
        for (player in instance.players) {
            player.noJump = false
            player.gravity = 0.08
        }
        setStartBarrier()
        state = State.WAITING
    }

    fun breakBlocksAroundPoint(point: Point, range: Double = 0.5, callback: XYZ.() -> Unit = {}) {
        point.forEachBlockAround(range) {
            val block = instance.getBlock(x, y, z)
            if (block == Block.BARRIER) {
                return@forEachBlockAround
            }
            val fallingBlock = Entity(EntityType.FALLING_BLOCK)
            val meta = fallingBlock.entityMeta as FallingBlockMeta
            meta.block = block
            val spawnPos = Pos(x.toDouble() + 0.5, y.toDouble() + 0.5 + 0.5, z.toDouble() + 0.5)
            instance.setBlock(x, y, z, Block.AIR)
            fallingBlock.setInstance(instance, spawnPos)
            fallingBlock.velocity = Vec(0.0, 10.0, 0.0).add(
                spawnPos.minus(point).withY(0.0).mul(5.0)
                    .add(Random.nextDouble(), 0.0, Random.nextDouble())
            )

            this.apply(callback)
        }
    }
}

var Player.noJump
    get() = getAttribute(Attribute.GENERIC_JUMP_STRENGTH).modifiers().isNotEmpty()
    set(value) {
        val mod = AttributeModifier(
            "no_jump",
            -100.0,
            AttributeOperation.ADD_VALUE
        )
        val attribute = getAttribute(Attribute.GENERIC_JUMP_STRENGTH)
        if (value) attribute.addModifier(mod) else attribute.removeModifier(mod.id)
    }

var Player.gravity
    get() = getAttribute(Attribute.GENERIC_GRAVITY).baseValue
    set(value) {
        getAttribute(Attribute.GENERIC_GRAVITY).baseValue = value
    }

data class XYZ(
    val x: Int,
    val y: Int,
    val z: Int,
)

fun Point.forEachBlockAround(range: Double = 0.5, block: XYZ.() -> Unit) {
    for (x in floor(x() - range).toInt()..ceil(x() + range).toInt()) {
        for (z in floor(z() - range).toInt()..ceil(z() + range).toInt()) {
            for (y in floor(y() - range).toInt()..ceil(y() + range).toInt()) {
                XYZ(x, y, z).apply(block)
            }
        }
    }
}

class JamPlayerData {
    companion object : VolatileData<UUID, JamPlayerData>(::JamPlayerData)

    var state = State.PLAYING
    var section = 0
    var lastPlayedFlySoundTick = -999
    var ticksAirborne = 0
    var rollingItem = false
    var isUsingItem = false

    enum class State {
        PLAYING,
        SPECTATING,
    }
}

val Player.data
    get() = JamPlayerData.getDataOrNew(uuid) {}

data object Config {
    val address: String = "0.0.0.0"
    var port: Int = 25565

    // In memory database / keep alive between connections/transactions
    var databaseURL: String = "jdbc:h2:mem:regular;DB_CLOSE_DELAY=-1;"
    var databaseDriver: String = "org.h2.Driver"
}

val db = Database.connect(Config.databaseURL, driver = Config.databaseDriver)

fun start() {
    val minecraftServer = MinecraftServer.init()

    registerAllBuiltinModules(EnumSet.of(BuiltinModuleType.CORE))
    registerAllAnnotatedFeatures(::start.javaClass.packageName)

    GlobalEventHandler.listen<AsyncPlayerConfigurationEvent> {
        spawningInstance = JamGame.instance
        player.respawnPoint = JamGame.defaultSpawnPoint
    }

    GlobalEventHandler.listen<ItemDropEvent> { isCancelled = true }

    JamGame.instance = JamGame.getFirstInstanceOrNew()
    JamGame.generateObstacles()
    JamGame.generateBonus()
    JamGame.setStartBarrier()

    minecraftServer.start(Config.address, Config.port)
}

fun <T> weightedRandom(vararg list: Pair<Double, T>): T {
    var totalWeight = 0.0
    for (i in list) {
        totalWeight += i.first
    }

    var idx = 0
    var r = Math.random() * totalWeight
    while (idx < list.size - 1) {
        r -= list[idx].first
        if (r <= 0.0) break
        ++idx
    }

    return list[idx].second
}
