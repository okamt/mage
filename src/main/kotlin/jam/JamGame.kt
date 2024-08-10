package helio.jam

import helio.module.*
import helio.util.listen
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import net.bladehunt.kotstom.GlobalEventHandler
import net.bladehunt.kotstom.SchedulerManager
import net.bladehunt.kotstom.extension.adventure.asComponent
import net.bladehunt.kotstom.extension.asVec
import net.bladehunt.kotstom.extension.times
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.WorldBorder
import net.minestom.server.instance.block.Block
import net.minestom.server.world.DimensionType
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Database
import java.util.*


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

    override val dimensionType =
        DimensionType.builder().ambientLight(1f).minY(DIMENSION_MIN_Y).height(DIMENSION_HEIGHT)
            .logicalHeight(DIMENSION_HEIGHT).effects("minecraft:nether").build()
    override val defaultSpawnPoint = Pos(0.0, (START_Y + 1).toDouble(), 0.0)

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
                    if (instance.players.find { it.position.y in (chicken.position.y - 100..chicken.position.y + 100) } != null) {
                        chicken.velocity = chicken.position.direction().times(5.0).asVec()
                    }
                    SchedulerManager.scheduleNextTick(::checkChicken)
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

    override suspend fun PlayerSpawnEvent.handle(data: Data) = coroutineScope {
        if (isFirstSpawn) {
            player.getAttribute(Attribute.GENERIC_GRAVITY).baseValue = 0.08 / 2
            when (state) {
                State.WAITING -> {
                    player.gameMode = GameMode.ADVENTURE
                    // TODO: Add logic to check if enough players
                    start()
                }

                State.ONGOING -> {
                    player.gameMode = GameMode.SPECTATOR
                }
            }
        }
    }

    fun setStartBarrier(instance: Instance = this.instance, block: Block = Block.BARRIER) {
        for (x in -RADIUS..RADIUS) {
            for (z in -RADIUS..RADIUS) {
                instance.setBlock(x, START_Y, z, block)
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
        state = State.ONGOING
        setStartBarrier(block = Block.AIR)
    }
}

@RegisterFeature(Players::class)
object JamPlayerData : PlayerDataDefinition<JamPlayerData.Data>(Data) {
    const val ID = "playerData"
    override val id = Id(ID)

    class Data(id: EntityID<UUID>) : PlayerData(id) {
        companion object : Class<Data>(Table)

        object Table : UUIDTable(ID) {
            val spectating = bool("spectating")
        }

        var spectating by Table.spectating
    }
}

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

    JamGame.instance = JamGame.getFirstInstanceOrNew()
    JamGame.generateObstacles()

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
