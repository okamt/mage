package helio.util.exposed

import net.minestom.server.coordinate.Pos
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.sql.Table

/**
 * A [CustomColumn] that stores a [Pos].
 */
class PosColumn(table: Table, id: String) : CustomColumn<PosColumn, PosColumn.Accessor>(table, id) {
    class Accessor(parent: PosColumn, entity: Entity<*>) :
        CustomColumn.Accessor<Accessor, PosColumn, Pos>(parent, entity) {
        var x by delegate(parent.x)
        var y by delegate(parent.y)
        var z by delegate(parent.z)
        var yaw by delegate(parent.yaw)
        var pitch by delegate(parent.pitch)

        override var value: Pos
            get() = Pos(x, y, z, yaw, pitch)
            set(value) {
                x = value.x
                y = value.y
                z = value.z
                yaw = value.yaw
                pitch = value.pitch
            }
    }

    override val accessor = ::Accessor

    val x = table.double("${id}_x")
    val y = table.double("${id}_y")
    val z = table.double("${id}_z")
    val yaw = table.float("${id}_yaw")
    val pitch = table.float("${id}_pitch")
}

/**
 * Shorthand for creating a [PosColumn].
 */
fun Table.pos(id: String) = PosColumn(this, id)

