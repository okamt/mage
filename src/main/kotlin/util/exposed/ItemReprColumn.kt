package helio.util.exposed

import helio.game.instance.DefaultInstance.Data.Table.nullable
import helio.module.FeatureDefinition
import helio.module.ItemRepr
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.sql.Table

/**
 * A [CustomColumn] that stores an [ItemRepr].
 */
class ItemReprColumn(table: Table, id: String) : CustomColumn<ItemReprColumn, ItemReprColumn.Accessor>(table, id) {
    class Accessor(parent: ItemReprColumn, entity: Entity<*>) :
        CustomColumn.Accessor<Accessor, ItemReprColumn, ItemRepr>(parent, entity) {
        var itemDefId by delegate(parent.itemDefId)
        var itemDataId by delegate(parent.itemDataId)

        override var value: ItemRepr
            get() = ItemRepr(FeatureDefinition.Id(itemDefId), itemDataId)
            set(value) {
                itemDefId = value.itemDefId.value
                itemDataId = value.itemDataId
            }
    }

    override val accessor = ::Accessor

    val itemDefId = table.varchar("${id}_def", FeatureDefinition.Id.MAX_LEN)
    val itemDataId = table.integer("${id}_data")

    /**
     * A nullable [ItemReprColumn].
     */
    class Nullable(table: Table, id: String) : CustomColumn<Nullable, Nullable.Accessor>(table, id) {
        class Accessor(parent: Nullable, entity: Entity<*>) :
            CustomColumn.Accessor<Accessor, Nullable, ItemRepr?>(parent, entity) {
            var itemDefId by delegate(parent.itemDefId)
            var itemDataId by delegate(parent.itemDataId)

            override var value: ItemRepr?
                get() {
                    val itemDefId = itemDefId
                    val itemDataId = itemDataId
                    return if (itemDefId == null || itemDataId == null) {
                        null
                    } else {
                        ItemRepr(FeatureDefinition.Id(itemDefId), itemDataId)
                    }
                }
                set(value) {
                    itemDefId = value?.itemDefId?.value
                    itemDataId = value?.itemDataId
                }
        }

        override val accessor = ::Accessor

        val itemDefId = table.varchar("${id}_def", FeatureDefinition.Id.MAX_LEN).nullable()
        val itemDataId = table.integer("${id}_data").nullable()
    }
}

/**
 * Shorthand for creating an [ItemReprColumn].
 */
fun Table.itemrepr(id: String) = ItemReprColumn(this, id)

