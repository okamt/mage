package helio.util.exposed

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A group of columns for Exposed's [Table]. Not an actual [IColumnType].
 *
 * @sample [ExampleColumnGroup]
 */
abstract class ColumnGroup<SELF : ColumnGroup<SELF, ACCESSOR>, ACCESSOR : ColumnGroup.Accessor<ACCESSOR, SELF, *>>(
    val table: Table,
    val id: String,
) {
    @Suppress("UNCHECKED_CAST")
    private val self
        get() = this as SELF

    /**
     * Accessor for a given [entity] to access the value(s) of a [ColumnGroup] ([parent]).
     */
    abstract class Accessor<SELF : Accessor<SELF, PARENT, VALUE>, PARENT : ColumnGroup<PARENT, SELF>, VALUE>(
        val parent: PARENT,
        val entity: Entity<*>
    ) {
        /**
         * Convenience function for creating a property delegate to access the value of a [Column].
         *
         * @sample [ExampleColumnGroup.Accessor]
         */
        fun <T> delegate(column: Column<T>) = entity.columnDelegate(column)

        abstract var value: VALUE
    }

    /**
     * Constructor for the [Accessor].
     *
     * @sample [ExampleColumnGroup.accessor]
     */
    abstract val accessor: (SELF, Entity<*>) -> ACCESSOR

    operator fun getValue(entity: Entity<*>, property: KProperty<*>): ACCESSOR = accessor(self, entity)
}

/**
 * A [ReadWriteProperty] to access the value of a [column] from a given [entity].
 *
 * Normally this is not possible since Exposed's [Column] delegate for [Entity] is not public.
 */
class EntityColumnDelegate<ID : Comparable<ID>, T>(private val entity: Entity<ID>, private val column: Column<T>) :
    ReadWriteProperty<Any?, T> {
    // Extension functions for accessing private getValue/setValue functions for [Column] in [Entity].
    fun Entity<ID>.getValue(column: Column<T>, prop: KProperty<*>): T = column.getValue(entity, prop)
    fun Entity<ID>.setValue(column: Column<T>, prop: KProperty<*>, value: T) = column.setValue(entity, prop, value)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = entity.getValue(column, property)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = entity.setValue(column, property, value)
}

/**
 * Shorthand for making an [EntityColumnDelegate].
 */
fun <ID : Comparable<ID>, T> Entity<ID>.columnDelegate(column: Column<T>): EntityColumnDelegate<ID, T> =
    EntityColumnDelegate(this, column)

private class ExampleColumnGroup(table: Table, id: String) :
    ColumnGroup<ExampleColumnGroup, ExampleColumnGroup.Accessor>(table, id) {
    class Accessor(parent: ExampleColumnGroup, entity: Entity<*>) :
        ColumnGroup.Accessor<Accessor, ExampleColumnGroup, Any>(parent, entity) {
        var testColumn1 by delegate(parent.testColumn1)
        var testColumn2 by delegate(parent.testColumn2)

        override var value: Any = 1
    }

    override val accessor = ::Accessor

    val testColumn1 = table.integer("myInteger")
    val testColumn2 = table.varchar("myVarchar", 50)
}