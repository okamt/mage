package io.github.okamt.mage.util.exposed

import io.github.okamt.mage.module.FeatureDefinition
import io.github.okamt.mage.module.ItemRepr
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Table.Dual.nullable

/**
 * A [ColumnGroup] that stores an [ItemRepr].
 */
class ItemReprColumnGroup(table: Table, id: String) :
    ColumnGroup<ItemReprColumnGroup, ItemReprColumnGroup.Accessor>(table, id) {
    class Accessor(parent: ItemReprColumnGroup, entity: Entity<*>) :
        ColumnGroup.Accessor<Accessor, ItemReprColumnGroup, ItemRepr>(parent, entity) {
        var itemDefId by delegate(parent.itemDefId)
        var itemDataId by delegate(parent.itemDataId)

        override var value: ItemRepr
            get() = ItemRepr(FeatureDefinition.Id(itemDefId), itemDataId)
            set(value) {
                itemDefId = value.defId.value
                itemDataId = value.dataId
            }
    }

    override val accessor = ::Accessor

    val itemDefId = table.varchar("${id}_def", FeatureDefinition.Id.MAX_LEN)
    val itemDataId = table.integer("${id}_data")

    /**
     * A nullable [ItemReprColumnGroup].
     */
    class Nullable(table: Table, id: String) : ColumnGroup<Nullable, Nullable.Accessor>(table, id) {
        class Accessor(parent: Nullable, entity: Entity<*>) :
            ColumnGroup.Accessor<Accessor, Nullable, ItemRepr?>(parent, entity) {
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
                    itemDefId = value?.defId?.value
                    itemDataId = value?.dataId
                }
        }

        override val accessor = ::Accessor

        val itemDefId = table.varchar("${id}_def", FeatureDefinition.Id.MAX_LEN).nullable()
        val itemDataId = table.integer("${id}_data").nullable()
    }
}

/**
 * Shorthand for creating an [ItemReprColumnGroup].
 */
fun Table.itemrepr(id: String) = ItemReprColumnGroup(this, id)

