package io.github.okamt.mage.util.exposed

import net.minestom.server.coordinate.Pos
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.sql.Table

/**
 * A [ColumnGroup] that stores a [Pos].
 */
class PosColumnGroup(table: Table, id: String) : ColumnGroup<PosColumnGroup, PosColumnGroup.Accessor>(table, id) {
    class Accessor(parent: PosColumnGroup, entity: Entity<*>) :
        ColumnGroup.Accessor<Accessor, PosColumnGroup, Pos>(parent, entity) {
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
 * Shorthand for creating a [PosColumnGroup].
 */
fun Table.pos(id: String) = PosColumnGroup(this, id)

