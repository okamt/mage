package helio.module

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.component.DataComponentMap
import net.minestom.server.event.Event
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.event.trait.PlayerInstanceEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

val ITEM_ID_TAG = Tag.String("itemId")
val ITEM_DATA_ID_TAG = Tag.Integer("itemDataId")

object Items : ServerModule("items"), FeatureRegistry<ItemDefinition<*>> {
    private val map: MutableMap<String, ItemDefinition<*>> = mutableMapOf()

    override fun register(definition: ItemDefinition<*>) {
        map[definition.id] = definition

        transaction {
            SchemaUtils.create(definition.data.table)
        }
    }

    fun getItemDefinition(id: String): ItemDefinition<*>? {
        return map[id]
    }

    fun getItemDefinition(itemStack: ItemStack): ItemDefinition<*> {
        val itemId = itemStack.getTag(ITEM_ID_TAG)
        return getItemDefinition(itemId)
            ?: throw Exception("ItemStack $itemStack has invalid ItemDefinition id $itemId")
    }

    override fun onRegisterModule() {
        eventNode.addListener(PlayerUseItemEvent::class.java) { it.player.itemInMainHand.itemDef.delegateEvent(it) }
        eventNode.addListener(PlayerBlockPlaceEvent::class.java) { it.player.itemInMainHand.itemDef.delegateEvent(it) }
    }
}

abstract class ItemData(id: EntityID<Int>) : FeatureData<Int>(id) {
    abstract class Class<DATA : ItemData>(
        table: IdTable<Int>,
    ) : FeatureData.Class<Int, DATA>(table)
}

abstract class ItemDefinition<DATA : ItemData>(
    dataClass: ItemData.Class<DATA>,
) : FeatureDefinition<Int, DATA>(dataClass) {
    abstract val material: Material

    internal fun delegateEvent(event: Event) {
        val itemStack = when (event) {
            is PlayerInstanceEvent -> event.player.itemInMainHand
            else -> error("Can't get ItemStack from event ${event::class.qualifiedName}.")
        }

        when (event) {
            is PlayerUseItemEvent -> event.handle(getData(itemStack))
            is PlayerBlockPlaceEvent -> event.handle(getData(itemStack))
        }
    }

    open fun PlayerUseItemEvent.handle(data: DATA) {}
    open fun PlayerBlockPlaceEvent.handle(data: DATA) {
        isCancelled = true
    }

    open fun onCreateItemStack(itemStack: ItemStack, itemData: DATA): ItemStack = itemStack

    fun getData(itemStack: ItemStack): DATA {
        val itemDataId = itemStack.getTag(ITEM_DATA_ID_TAG)
        return transaction { data.findById(itemDataId) }
            ?: error("ItemStack $itemStack has no associated ItemData for ItemDefinition $id")
    }

    @Suppress("UnstableApiUsage")
    fun createItemStack(): ItemStack {
        var itemStack = ItemStack.AIR

        transaction {
            val itemData = data.new {}

            itemStack = ItemStack
                .of(material, DataComponentMap.EMPTY)
                .withCustomName(Component.translatable(this@ItemDefinition.id).decoration(TextDecoration.ITALIC, false))
                .withTag(ITEM_ID_TAG, this@ItemDefinition.id)
                .withTag(ITEM_DATA_ID_TAG, itemData.id.value)

            itemStack = onCreateItemStack(itemStack, itemData)
        }

        return itemStack
    }
}

val ItemStack.itemDef: ItemDefinition<*>
    get() = Items.getItemDefinition(this)