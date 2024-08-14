package helio.jam

import helio.module.*
import helio.util.listen
import helio.util.only
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.sync.Mutex
import net.bladehunt.kotstom.*
import net.bladehunt.kotstom.coroutines.MinestomDispatcher
import net.bladehunt.kotstom.dsl.kommand.buildSyntax
import net.bladehunt.kotstom.dsl.kommand.kommand
import net.bladehunt.kotstom.dsl.particle
import net.bladehunt.kotstom.extension.*
import net.bladehunt.kotstom.extension.adventure.asComponent
import net.bladehunt.kotstom.extension.adventure.asMini
import net.bladehunt.kotstom.extension.adventure.color
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.arguments.ArgumentString
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
import net.minestom.server.event.inventory.InventoryPreClickEvent
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.WorldBorder
import net.minestom.server.instance.block.Block
import net.minestom.server.network.packet.server.play.WorldBorderCenterPacket
import net.minestom.server.network.packet.server.play.WorldBorderLerpSizePacket
import net.minestom.server.particle.Particle
import net.minestom.server.potion.Potion
import net.minestom.server.potion.PotionEffect
import net.minestom.server.registry.DynamicRegistry
import net.minestom.server.scoreboard.Sidebar
import net.minestom.server.sound.SoundEvent
import net.minestom.server.tag.Tag
import net.minestom.server.world.DimensionType
import net.minestom.server.world.biome.Biome
import net.minestom.server.world.biome.BiomeEffects
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.Database
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.TimeSource

@RegisterFeature(Instances::class)
object JamGame : InstanceDefinition<JamGame.Data>(Data) {
    const val ID = "gameInstance"
    override val id = Id(ID)

    const val PLAYERS_TO_START = 1

    const val RADIUS = 32
    const val DIMENSION_MAX_Y = 1007
    const val DIMENSION_MIN_Y = 0

    //const val DIMENSION_MIN_Y = -2032
    const val DIMENSION_HEIGHT = 1008
    const val START_Y = DIMENSION_MAX_Y - 64
    const val END_Y = DIMENSION_MIN_Y + 64
    const val START_BARRIER_Y_OFFSET = 32
    const val TOTAL_SECTIONS = 4
    val sectionOffset = Vec(1000.0, 0.0, 0.0)

    var started = TimeSource.Monotonic.markNow()

    override val dimensionType =
        DimensionType.builder().ambientLight(1f).minY(DIMENSION_MIN_Y).height(DIMENSION_HEIGHT).fixedTime(6000)
            .logicalHeight(DIMENSION_HEIGHT).build()

    override val defaultSpawnPoint = Pos(0.0, (START_Y + START_BARRIER_Y_OFFSET + 1).toDouble(), 0.0)
    //override val defaultSpawnPoint = Pos(0.0, 50.0, 0.0)

    enum class State {
        WAITING,
        ONGOING,
    }

    var state = State.WAITING
    lateinit var instance: Instance

    fun generateEndPlatform() {
        val pos = sectionOffset * (TOTAL_SECTIONS - 1).toDouble()
        for (x in pos.x.toInt() - RADIUS..pos.x.toInt() + RADIUS) {
            for (z in pos.z.toInt() - RADIUS..pos.z.toInt() + RADIUS) {
                instance.setBlock(x, END_Y - 32, z, Block.GRASS_BLOCK)
            }
        }
        instance.setBlock(pos.x.toInt(), END_Y - 32 + 1, pos.z.toInt(), Block.CAKE)
    }

    private fun makeZoneBiome(color: Int): DynamicRegistry.Key<Biome> =
        BiomeRegistry.register(
            color.toString(),
            Biome.builder().effects(BiomeEffects.builder().skyColor(color).build()).build()
        )

    enum class Zone(
        val colorName: String,
        val description: String,
        val concreteBlock: Block,
        val color: Int,
        val bossbarColor: BossBar.Color,
        val biome: DynamicRegistry.Key<Biome> = makeZoneBiome(color),
    ) {
        PINK(
            "Pink",
            "All items have stronger effect.",
            Block.PINK_CONCRETE,
            0xd946ef,
            BossBar.Color.PINK,
        ),

        BLUE(
            "Blue",
            "Players are bigger.",
            Block.BLUE_CONCRETE,
            0x3b82f6,
            BossBar.Color.BLUE,
        ),

        RED(
            "Red",
            "Faster gravity.",
            Block.RED_CONCRETE,
            0xef4444,
            BossBar.Color.RED,
        ),

        GREEN(
            "Green",
            "Smaller border.",
            Block.GREEN_CONCRETE,
            0x22c55e,
            BossBar.Color.GREEN,
        ),

        YELLOW(
            "Yellow",
            "Hitting obstacles gives boost.",
            Block.YELLOW_CONCRETE,
            0xfde047,
            BossBar.Color.YELLOW,
        ),

        PURPLE(
            "Purple",
            "More obstacles.",
            Block.PURPLE_CONCRETE,
            0xa855f7,
            BossBar.Color.PURPLE,
        ),

        WHITE(
            "White",
            "No effect.",
            Block.WHITE_CONCRETE,
            0xfafafa,
            BossBar.Color.WHITE,
        );

        fun applyEffect(player: Player) {
            when (this) {
                PINK -> {
                    player.data.pink = true
                }

                BLUE -> {
                    player.getAttribute(Attribute.GENERIC_SCALE).baseValue = 3.0
                }

                RED -> {
                    player.gravity = 0.08 * 2.0
                }

                GREEN -> {
                    player.sendPacket(WorldBorderLerpSizePacket(RADIUS * 2.0, RADIUS.toDouble() / 2.0, 100))
                }

                YELLOW -> {
                    player.data.yellow = true
                }

                PURPLE -> {}

                WHITE -> {}
            }
        }

        fun unapplyEffect(player: Player) {
            when (this) {
                PINK -> {
                    player.data.pink = false
                }

                BLUE -> {
                    player.getAttribute(Attribute.GENERIC_SCALE).baseValue = 1.0
                }

                RED -> {
                    player.gravity = 0.08 / 2.0
                }

                GREEN -> {
                    player.sendPacket(WorldBorderLerpSizePacket(RADIUS.toDouble() / 2.0, RADIUS * 2.0, 100))
                }

                YELLOW -> {
                    player.data.yellow = false
                }

                PURPLE -> {}

                WHITE -> {}
            }
        }
    }

    private fun setSectionZone(section: Int, zone: Zone) {
        val startPos = sectionOffset * section.toDouble()
        for (xOffset in -2..2) {
            for (zOffset in -2..2) {
                val pos = startPos + Vec(xOffset * 16.0, 0.0, zOffset * 16.0)
                instance.loadChunk(pos).thenRun {
                    val chunk = instance.getChunkAt(pos)!!
                    for (blockX in 0..15)
                        for (blockZ in 0..15)
                            for (blockY in DIMENSION_MIN_Y..DIMENSION_MAX_Y)
                                chunk.setBiome(blockX, blockY, blockZ, zone.biome)
                }
            }
        }
    }

    var sections = mutableListOf<Zone>()

    fun generateSections() {
        sections.clear()
        for (i in 0..<TOTAL_SECTIONS) {
            var zone: Zone
            do {
                zone = Zone.entries.random()
            } while (zone in sections)
            sections.add(zone)
        }
        //sections.forEachIndexed { i, zone -> setSectionZone(i, zone) }
    }

    suspend fun done() {
        val top3 = instance.players
            .filter { it.data.state == JamPlayerData.State.PLAYING }
            .map { it to it.data.time!! }
            .sortedWith(compareBy<Pair<Player, Duration>> { it.second })
            .take(3)

        fun getBadge(pos: Int): String = when (pos) {
            1 -> "<yellow>"
            2 -> "<gray>"
            3 -> "<#964B00>"
            else -> error("No badge for ${pos.ordinal()} place.")
        } + "[${pos.ordinal()}]<reset>"

        instance.sendMessage(
            "\n     <bold>RESULTS</bold>\n\n${
                top3.mapIndexed { i, pair ->
                    "     " + getBadge(i + 1) + " " + pair.first.username + " in " + pair.second.neat + "\n"
                }
                    .joinToString("")
            }".asMini()
        )
        delay(1000 * 5)
        instance.sendMessage("Restarting in 10 seconds!".asComponent())
        delay(1000 * 10)
        reset()
    }

    suspend fun moveToNextSection(player: Player) = coroutineScope {
        if (player.data.section + 1 >= TOTAL_SECTIONS) {
            player.inventory.clear()
            val time = started.elapsedNow()
            instance.sendMessage("<yellow>${player.username}</yellow> reached the bottom in <yellow>${time.neat}</yellow>!".asMini())
            val zone = sections[player.data.section]
            zone.unapplyEffect(player)
            player.noJump = false
            player.data.time = time
            player.isGlowing = false
            player.gravity = 0.08

            player.playSound(
                Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_BELL, Sound.Source.AMBIENT, 1f, 1f),
                Sound.Emitter.self()
            )

            if (instance.players.filter { it.data.state == JamPlayerData.State.PLAYING }.all { it.data.time != null }) {
                launch { done() }
            }

            return@coroutineScope
        }

        player.data.section += 1

        sections[player.data.section - 1].unapplyEffect(player)
        val zone = sections[player.data.section]

        val pos = sectionOffset * player.data.section.toDouble()
        player.addEffect(Potion(PotionEffect.BLINDNESS, 3, 20 * 3))
        for (entity in instance.entities) {
            if (player in entity.passengers) {
                entity.removePassenger(player)
                entity.remove()
            }
        }
        player.passengers.forEach { player.removePassenger(it) }
        player.data.isUsingItem = false
        player.teleport(
            (player.position.sub(sectionOffset * player.data.section.dec().toDouble())).add(pos)
                .withY((START_Y + START_BARRIER_Y_OFFSET + 100 + 1).toDouble())
                //.withX { if (zone == Zone.GREEN) it.coerceIn(pos.x() + (-RADIUS / 2.0)..pos.x() + (RADIUS / 2.0)) else it }
                //.withZ { if (zone == Zone.GREEN) it.coerceIn(pos.z() + (-RADIUS / 2.0)..pos.z() + (RADIUS / 2.0)) else it }
                .withX { if (zone == Zone.GREEN) pos.x() else it }
                .withZ { if (zone == Zone.GREEN) pos.z() else it }
        )
        SchedulerManager.scheduleNextTick {
            player.sendPacket(WorldBorderCenterPacket(pos.x(), pos.z()))
            zone.applyEffect(player)
        }
        player.showTitle(
            Title.title(
                "${zone.name} Zone".color(TextColor.color(zone.color)).decorate(TextDecoration.BOLD),
                zone.description.color(TextColor.color(0x71717a))
            )
        )
        delay(3000)
        player.data.moving = false
    }

    sealed interface Obstacle {
        companion object {
            fun random(): Obstacle = Obstacle::class.sealedSubclasses.map { it.objectInstance }.random() as Obstacle

            fun randomBlock() =
                listOf(
                    Block.PINK_CONCRETE,
                    Block.LIGHT_BLUE_CONCRETE,
                    Block.RED_CONCRETE,
                    Block.LIME_CONCRETE,
                    Block.YELLOW_CONCRETE,
                    Block.PINK_CONCRETE,
                    Block.WHITE_CONCRETE,
                ).random()
        }

        fun generate(instance: Instance, pos: XYZ)

        data object Rectangle : Obstacle {
            const val MAX_SIZE = 40
            const val OUT = 10

            override fun generate(instance: Instance, pos: XYZ) {
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
                        instance.setBlock(pos.x + x, pos.y, pos.z + z, block)
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

            override fun generate(instance: Instance, pos: XYZ) {
                val quadrants = mutableListOf(
                    Vec(1.0, 1.0, 1.0),
                    Vec(-1.0, 1.0, 1.0),
                    Vec(-1.0, 1.0, -1.0),
                    Vec(1.0, 1.0, -1.0),
                )
                for (yOffset in -1..(-1..1).random()) {
                    val quadrant = quadrants.random()
                    quadrants.remove(quadrant)

                    val block = randomBlock()
                    val shape = shapes.random()
                    val radius = (shape.size / 2.0).toInt()
                    val centerX = (1..RADIUS).random().times(quadrant.x.toInt())
                    val centerZ = (1..RADIUS).random().times(quadrant.z.toInt())
                    for ((shapeX, x) in shape.indices.zip(centerX - radius..centerX + radius)) {
                        for ((shapeZ, z) in shape.indices.zip(centerZ - radius..centerZ + radius)) {
                            if (shape[shapeZ][shapeX] == '#') {
                                instance.setBlock(pos.x + x, pos.y + yOffset, pos.z + z, block)
                            }
                        }
                    }
                }
            }
        }

        data object Grid : Obstacle {
            const val MIN_GRID_GAP = 3
            const val MAX_GRID_GAP = 10

            override fun generate(instance: Instance, pos: XYZ) {
                val block = randomBlock()
                val gap = (MIN_GRID_GAP..MAX_GRID_GAP).random()
                for (x in -RADIUS..RADIUS step gap) {
                    for (z in -RADIUS..RADIUS) {
                        instance.setBlock(pos.x + x, pos.y, pos.z + z, block)
                    }
                }
                for (z in -RADIUS..RADIUS step gap) {
                    for (x in -RADIUS..RADIUS) {
                        instance.setBlock(pos.x + x, pos.y, pos.z + z, block)
                    }
                }
            }
        }

        data object Chicken : Obstacle {
            override fun generate(instance: Instance, pos: XYZ) {
                val x = (-RADIUS..RADIUS).random()
                val z = (-RADIUS..RADIUS).random()
                val chicken = EntityCreature(EntityType.CHICKEN)
                chicken.setNoGravity(true)
                chicken.getAttribute(Attribute.GENERIC_SCALE).baseValue = 3.0
                val centerishX = (-RADIUS / 4..RADIUS / 4).random()
                val centerishZ = (-RADIUS / 4..RADIUS / 4).random()
                chicken.setInstance(
                    instance,
                    Pos((pos.x + x).toDouble(), pos.y.toDouble(), (pos.z + z).toDouble()).withLookAt(
                        Pos(
                            centerishX.toDouble(),
                            pos.y.toDouble(),
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
        //instanceContainer.worldBorder = WorldBorder((RADIUS * 2).toDouble(), 0.0, 0.0, 0, 0)
        instanceContainer.timeRate = 0

        generateSections()

        val sectionPoints = (0..<TOTAL_SECTIONS).map { sectionOffset * it.toDouble() }
        instanceContainer.setGenerator { unit ->
            val units = unit.subdivide()
            assert(units.all { it.size().x() == 16.0 && it.size().z() == 16.0 })
            units.forEach { subunit ->
                var sectionIndex =
                    sectionPoints.indexOfFirst { it.withY(0.0).distance(subunit.absoluteStart().withY(0.0)) < 100.0 }
                if (sectionIndex == -1) return@forEach
                val zone = sections[sectionIndex]
                subunit.modifier().fillBiome(zone.biome)
            }
        }

        instance = instanceContainer

        generateObstacles()
        generateBonus()
        setStartBarrier()
        generateEndPlatform()

        return instanceContainer
    }

    fun getHeight(player: Player): Float = when (player.data.state) {
        JamPlayerData.State.PLAYING -> {
            val oneSection = 1f / TOTAL_SECTIONS
            val playerDepthInCurrentSection =
                ((1f - ((player.position.y.toFloat().coerceIn(
                    END_Y.toFloat(),
                    START_Y.toFloat()
                ) - END_Y) / (START_Y - END_Y))) / TOTAL_SECTIONS).coerceIn(0f..oneSection)
            // 2000 -2000  2000 = 0
            // 2000 -2000  1000 = 0.25
            // 2000 -2000  0 = 0.5
            1f - (oneSection * player.data.section.toFloat() + playerDepthInCurrentSection).coerceIn(0f..1f)
        }

        JamPlayerData.State.SPECTATING -> {
            1f
        }
    }

    val bossbars = mutableMapOf<UUID, BossBar>()
    var tick = 0
    val flySound = Sound.sound(SoundEvent.ITEM_ELYTRA_FLYING, Sound.Source.AMBIENT, 1f, 0.8f)
    const val FLY_SOUND_AIRBORNE_TICKS_THRESHOLD = 20 * 2
    const val FLY_SOUND_DURATION_TICKS = 20 * 10

    override suspend fun onTick() {
        val elapsed = started.elapsedNow()
        val actionBar = elapsed.neat.asComponent()
        var heights = mutableListOf<Pair<Player, Float>>()

        for (player in instance.players) {
            val height = getHeight(player)
            if (state == State.ONGOING && player.data.state == JamPlayerData.State.PLAYING) {
                heights.add(player to height)
            }
            val playerTime = player.data.time
            if (playerTime != null) {
                player.sendActionBar(playerTime.neat.asComponent())
            } else {
                player.sendActionBar(actionBar)
            }

            val bar = bossbars.getOrPut(player.uuid) {
                val zone = sections[player.data.section]
                BossBar.bossBar(
                    "Height".color(TextColor.color(zone.color)),
                    height,
                    zone.bossbarColor,
                    BossBar.Overlay.PROGRESS
                ).addViewer(player)
            }
            bar.progress(height)
            val zone = sections[player.data.section]
            bar.color(zone.bossbarColor)
            bar.name("Height".color(TextColor.color(zone.color)))

            if (player.data.state == JamPlayerData.State.PLAYING && state == State.ONGOING) {
                if ((tick - player.data.lastPlayedFlySoundTick) >= FLY_SOUND_DURATION_TICKS && player.data.ticksAirborne >= FLY_SOUND_AIRBORNE_TICKS_THRESHOLD) {
                    player.data.lastPlayedFlySoundTick = tick
                    player.stopSound(flySound)
                    player.playSound(flySound)
                }

                if (player.position.y <= (END_Y + 16)) {
                    if (!player.data.moving) {
                        player.data.moving = true
                        coroutineScope {
                            async {
                                moveToNextSection(player)
                            }
                        }
                    }
                }
            }

            if (state == State.ONGOING && player.data.state == JamPlayerData.State.PLAYING && player.isOnGround && !player.data.moving) {
                val zone = sections[player.data.section]
                val brokeAnything = breakBlocksAroundPoint(player.position, range = if (zone == Zone.BLUE) 1.5 else 0.5)
                if (brokeAnything) {
                    player.teleport(player.position.sub(0.0, 0.25, 0.0))
                    player.playSound(
                        Sound.sound(
                            SoundEvent.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR,
                            Sound.Source.AMBIENT,
                            1f,
                            if (player.getAttribute(Attribute.GENERIC_SCALE).baseValue > 1.0) 0.75f else 1f
                        ),
                        Sound.Emitter.self()
                    )
                    if (!player.data.yellow) {
                        player.damage(DamageType.FALL, 0f)
                    }
                    if (player.data.yellow) {
                        player.velocity = player.velocity.withY { it - 50.0 }
                    }
                }
            }

            if (player.isOnGround && !player.data.yellow) {
                player.data.ticksAirborne = 0
                player.data.lastPlayedFlySoundTick = -999
                player.stopSound(flySound)
            } else {
                player.data.ticksAirborne += 1
            }
        }

        val sortedHeights = heights.sortedWith(compareBy<Pair<Player, Float>> { it.second }.reversed())
        val gotIt = mutableSetOf<Player>()
        for (player in instance.players) {
            val sidebar = player.data.sidebar
            if (!sidebar.isViewer(player)) {
                sidebar.addViewer(player)
            }

            //fun heightToLine(height: Float): Int = (1_000_000.0 * height).roundToInt()

            if (sidebar.lines.isEmpty() || instance.players.any { player1 -> player1.data.state == JamPlayerData.State.PLAYING && player1.uuid.toString() !in sidebar.lines.map { it.id } }) {
                for (line in sidebar.lines) {
                    sidebar.removeLine(line.id)
                }
                var i = sortedHeights.size
                for ((player2, height) in sortedHeights) {
                    sidebar.createLine(
                        Sidebar.ScoreboardLine(
                            player2.uuid.toString(),
                            if (player == player2) "<yellow>${player2.username}".asMini() else "<white>${player2.username}".asMini(),
                            i
                        )
                    )
                    i -= 1
                }
            } else {
                var i = sortedHeights.size
                for (line in sidebar.lines) {
                    val player1 = instance.players.find { line.id == it.uuid.toString() }
                    if (player1 == null || player1.data.state == JamPlayerData.State.SPECTATING || player1.gameMode == GameMode.SPECTATOR) {
                        sidebar.removeLine(line.id)
                    }
                }
                var lastPlayer: Player? = null
                for ((player2, height) in sortedHeights) {
                    val line = sidebar.getLine(player2.uuid.toString())!!
                    val player = instance.players.find { it.uuid.toString() == line.id }
                    if (player == null) {
                        sidebar.lines.remove(line)
                        continue
                    } else {
                        val height = heights.find { it.first == player } ?: continue
                        val oldScore = line.line
                        sidebar.updateLineScore(line.id, i)
                        if (player2 == player && !player.data.moving) {
                            if (oldScore > i && lastPlayer != null && !lastPlayer.data.moving && player !in gotIt) {
                                player.sendMessage("<green>You passed ${lastPlayer.username}! (now in ${i.ordinal()})".asMini())
                                player.playSound(
                                    Sound.sound(
                                        SoundEvent.BLOCK_NOTE_BLOCK_BELL,
                                        Sound.Source.AMBIENT,
                                        1f,
                                        1f
                                    )
                                )
                                gotIt.add(player)
                            } else if (oldScore < i) {
                                val nextPlayer =
                                    sortedHeights.getOrNull(sortedHeights.indexOfFirst { it.first == player2 } + 1)?.first
                                if (nextPlayer != null && !nextPlayer.data.moving && player !in gotIt) {
                                    player.sendMessage("<red>${nextPlayer.username} passed you! (now in ${i.ordinal()})".asMini())
                                    player.playSound(
                                        Sound.sound(
                                            SoundEvent.BLOCK_NOTE_BLOCK_BELL,
                                            Sound.Source.AMBIENT,
                                            1f,
                                            0.5f
                                        )
                                    )
                                    gotIt.add(player)
                                }
                            }
                        }
                    }
                    i -= 1
                    lastPlayer = player2
                }
            }
        }

        tick += 1
    }

    var starting = Mutex()

    override suspend fun PlayerSpawnEvent.handle(data: Data) = coroutineScope {
        if (!player.data.sidebar.isViewer(player)) {
            player.data.sidebar.addViewer(player)
        }

        player.sendPacket(
            WorldBorder((RADIUS * 2).toDouble(), 0.0, 0.0, 0, 0).createInitializePacket(RADIUS * 2.0, 100)
        )

        when (state) {
            State.WAITING -> {
                player.gameMode = GameMode.ADVENTURE
                player.data.state = JamPlayerData.State.PLAYING

                instance.sendMessage("<yellow>${player.username}</yellow> has joined the game.".asMini())

                if (ConnectionManager.onlinePlayerCount >= PLAYERS_TO_START && starting.tryLock()) {
                    launch {
                        countdown()
                        //start()
                        starting.unlock()
                    }
                }
            }

            State.ONGOING -> {
                player.gameMode = GameMode.SPECTATOR
                player.data.state = JamPlayerData.State.SPECTATING
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
        for (sectionI in 0..<TOTAL_SECTIONS) {
            val zone = sections[sectionI]
            val pos = sectionOffset * sectionI.toDouble()
            for (y in START_Y downTo END_Y step (if (zone == Zone.PURPLE) 2 else 5)) {
                weightedRandom(
                    1.0 to Obstacle.Rectangle,
                    1.0 to Obstacle.Circle,
                    0.1 to Obstacle.Grid,
                    0.05 to Obstacle.Chicken
                ).generate(instance, pos.withY(y.toDouble()).xyz)
            }
        }
    }

    suspend fun giveItem(player: Player) {
        if (player.data.rollingItem) {
            return
        }
        player.data.rollingItem = true

        for (i in 0..20) {
            player.playSound(
                Sound.sound(SoundEvent.BLOCK_LEVER_CLICK, Sound.Source.NEUTRAL, 1f, 1f),
                Sound.Emitter.self()
            )
            delay(50)
        }
        player.playSound(
            Sound.sound(
                SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP,
                Sound.Source.AMBIENT,
                1f,
                1f
            ),
            Sound.Emitter.self()
        )
        player.inventory.clear()
        val item = getRandomItem()
        for (i in 0..8) {
            player.inventory.addItemStack(item.withTag(Tag.UUID("distinct"), UUID.randomUUID()))
        }

        player.data.rollingItem = false
    }

    fun generateBonus() {
        val margin = 12
        val gap = 10.0

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
                        player.playSound(
                            Sound.sound(
                                SoundEvent.ENTITY_EXPERIENCE_ORB_PICKUP,
                                Sound.Source.AMBIENT,
                                1f,
                                1f
                            ),
                            Sound.Emitter.self()
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

        for (sectionI in 0..<TOTAL_SECTIONS) {
            val pos = sectionOffset * sectionI.toDouble()
            for (y in START_Y - 200 downTo END_Y step 200) {
                var x = 0
                var z = 0
                do {
                    x = pos.x().toInt() + (-RADIUS + margin..RADIUS - margin).random()
                    z = pos.z().toInt() + (-RADIUS + margin..RADIUS - margin).random()
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
    }

    suspend fun countdown() {
        for (i in 10 downTo 1) {
            if (instance.players.size == 0) {
                return
            }

            instance.playSound(
                Sound.sound(SoundEvent.BLOCK_LEVER_CLICK, Sound.Source.AMBIENT, 1f, 1f),
                Sound.Emitter.self()
            )
            instance.sendMessage("Starting in $i seconds.".asComponent())

            if (i in 1..3) {
                instance.playSound(
                    Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_BELL, Sound.Source.AMBIENT, 1f, 1f),
                    Sound.Emitter.self()
                )
                instance.showTitle(Title.title(i.toString().asComponent(), "".asComponent()))
            }

            delay(1000)
        }
        start()
    }

    fun start() {
        if (instance.players.size == 0) {
            return
        }

        for (player in instance.players) {
            player.data.state = JamPlayerData.State.PLAYING
            player.data.moving = false
            player.noJump = true
            player.gravity = 0.08 / 2.0
            player.isGlowing = true
            val zone = sections[0]
            zone.applyEffect(player)
            if (zone == Zone.GREEN) {
                player.teleport(player.position.withX(0.0).withZ(0.0))
            }
            player.showTitle(
                Title.title(
                    "${zone.name} Zone".color(TextColor.color(zone.color)).decorate(TextDecoration.BOLD),
                    zone.description.color(TextColor.color(0x71717a))
                )
            )
        }
        setStartBarrier(block = Block.AIR)
        state = State.ONGOING
        started = TimeSource.Monotonic.markNow()
    }

    suspend fun reset() {
        for (player in instance.players) {
            player.noJump = false
            player.gravity = 0.08
            player.isGlowing = false
            val zone = sections[player.data.section]
            zone.unapplyEffect(player)
            player.data.sidebar.removeViewer(player)
            JamPlayerData.writeData(player.uuid) {}
        }
        state = State.WAITING
        val oldInstance = instance
        instance = JamGame.createInstanceContainer()
        oldInstance.players.map { it.setInstance(instance, defaultSpawnPoint).asDeferred() }.awaitAll()
        InstanceManager.unregisterInstance(oldInstance)
    }

    fun breakBlocksAroundPoint(
        point: Point,
        range: Double = 0.5,
        tnt: Boolean = false,
        callback: XYZ.() -> Unit = {}
    ): Boolean {
        var brokeAnything = false
        point.forEachBlockAround(range, tnt) {
            val block = instance.getBlock(x, y, z)
            if (block == Block.BARRIER || block.isAir) {
                return@forEachBlockAround
            }
            brokeAnything = true
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

            instance.sendGroupedPacket(particle {
                particle = Particle.BLOCK.withBlock(block)
                position = Vec(x.toDouble(), y.toDouble(), z.toDouble())
                count = 5
            })

            this.apply(callback)
        }
        return brokeAnything
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

fun Point.forEachBlockAround(range: Double = 0.5, tnt: Boolean = false, block: XYZ.() -> Unit) {
    val extraY = if (tnt) 20.0 else 0.0
    for (x in floor(x() - range).toInt()..ceil(x() + range).toInt()) {
        for (z in floor(z() - range).toInt()..ceil(z() + range).toInt()) {
            for (y in floor(y() - range - extraY).toInt()..ceil(y() + range + extraY).toInt()) {
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
    var moving = false
    var pink = false
    var yellow = false
    var time: Duration? = null
    var sidebar = Sidebar("<rainbow><bold>FREE FALL".asMini())

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

    registerAllBuiltinModules(BuiltinModuleType.CORE.only)
    registerAllAnnotatedFeatures(::start.javaClass.packageName)

    GlobalEventHandler.listen<AsyncPlayerConfigurationEvent> {
        spawningInstance = JamGame.instance
        player.respawnPoint = JamGame.defaultSpawnPoint
    }

    GlobalEventHandler.listen<PlayerDisconnectEvent> {
        JamGame.instance.sendMessage("<yellow>${player.username}</yellow> has left the game.".asMini())
    }

    GlobalEventHandler.listen<ItemDropEvent> { isCancelled = true }

    GlobalEventHandler.listen<InventoryPreClickEvent> { isCancelled = true }

    JamGame.createInstanceContainer()

    System.setProperty("minestom.chunk-view-distance", 2.toString())

    listOf(
        kommand {
            name = "debug_item"

            val item = ArgumentString("item")

            defaultExecutorAsync {
                JamGame.giveItem(player)
            }

            buildSyntax(item) {
                onlyPlayers()
                executor {
                    player.inventory.addItemStack(
                        when (item().lowercase()) {
                            "anvil" -> AnvilItem
                            "ball" -> BallItem
                            "tnt" -> TNTItem
                            "dash" -> DashItem
                            else -> {
                                player.sendMessage("Invalid item!")
                                return@executor
                            }
                        }.createItemStack()
                    )
                }
            }
        },
        kommand {
            name = "debug_next"

            defaultExecutorAsync {
                JamGame.moveToNextSection(player)
            }
        },
        kommand {
            name = "debug_reset"

            defaultExecutorAsync {
                JamGame.reset()
            }
        },
    ).forEach { CommandManager.register(it) }

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

val Point.xyz
    get() = XYZ(x().toInt(), y().toInt(), z().toInt())

val Duration.neat: String
    get() =
        String.format(Locale.US, "%02d:%05.2f", inWholeMinutes, (inWholeMilliseconds / 1000.0) % 60.0)

fun Int.ordinal() = "$this" + when {
    (this % 100 in 11..13) -> "th"
    (this % 10) == 1 -> "st"
    (this % 10) == 2 -> "nd"
    (this % 10) == 3 -> "rd"
    else -> "th"
}