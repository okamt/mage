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

val ITEM_DEF_ID_TAG = Tag.String("itemId")
val ITEM_DATA_ID_TAG = Tag.Integer("itemDataId")

/**
 * The items module.
 *
 * Handles delegating events to the appropriate [ItemDefinition]s.
 */
object Items : ServerModule("items"), FeatureRegistry<ItemDefinition<*>> {
    private val map: MutableMap<FeatureDefinition.Id, ItemDefinition<*>> = mutableMapOf()

    override fun onRegister(definition: ItemDefinition<*>) {
        map[definition.id] = definition

        transaction {
            SchemaUtils.create(definition.data.table)
        }
    }

    fun getItemDefinition(id: FeatureDefinition.Id): ItemDefinition<*>? = map[id]

    fun getItemDefinition(itemStack: ItemStack): ItemDefinition<*> {
        val itemDefId =
            FeatureDefinition.Id(requireNotNull(itemStack.getTag(ITEM_DEF_ID_TAG)) { "ItemStack $itemStack has no ItemDefinition." })
        return requireNotNull(getItemDefinition(itemDefId)) { "ItemStack $itemStack has invalid ItemDefinition id $itemDefId." }
    }

    override fun onRegisterModule() {
        eventNode.addListener(PlayerUseItemEvent::class.java) { it.player.itemInMainHand.itemDef.delegateEvent(it) }
        eventNode.addListener(PlayerBlockPlaceEvent::class.java) { it.player.itemInMainHand.itemDef.delegateEvent(it) }
    }
}

typealias ItemDataId = Int

abstract class ItemData(id: EntityID<ItemDataId>) : FeatureData<ItemDataId>(id) {
    abstract class Class<DATA : ItemData>(
        table: IdTable<ItemDataId>,
    ) : FeatureData.Class<ItemDataId, DATA>(table)
}

abstract class ItemDefinition<DATA : ItemData>(
    dataClass: ItemData.Class<DATA>,
) : FeatureDefinition<ItemDataId, DATA>(dataClass) {
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
            ?: error("ItemStack $itemStack has no associated ItemData for ItemDefinition $id.")
    }

    @Suppress("UnstableApiUsage")
    fun createItemStack(withData: DATA? = null): ItemStack {
        var itemStack = ItemStack.AIR

        transaction {
            val itemData = withData ?: data.new {}

            itemStack = ItemStack
                .of(material, DataComponentMap.EMPTY)
                .withCustomName(
                    Component.translatable(this@ItemDefinition.id.value).decoration(TextDecoration.ITALIC, false)
                )
                .withTag(ITEM_DEF_ID_TAG, this@ItemDefinition.id.value)
                .withTag(ITEM_DATA_ID_TAG, itemData.id.value)

            itemStack = onCreateItemStack(itemStack, itemData)
        }

        return itemStack
    }
}

val ItemStack.itemDef: ItemDefinition<*>
    get() = Items.getItemDefinition(this)

/**
 * Small representation of an [ItemStack].
 */
data class ItemRepr(
    val itemDefId: FeatureDefinition.Id,
    val itemDataId: ItemDataId,
) {
    val itemDef by lazy {
        requireNotNull(Items.getItemDefinition(itemDefId)) { "ItemRepr has invalid ItemDefinition id $itemDefId." }
    }

    val itemData by lazy {
        requireNotNull(itemDef.getData(itemDataId)) { "ItemRepr has invalid ItemData id $itemDataId for ItemDefinition $itemDefId." }
    }

    fun createItemStack(): ItemStack =
        /**
         * We know that itemData is of type DATA (in ItemDefinition<DATA>) because we got it from calling itemDef.getData
         * which always returns DATA?, so calling createItemStack with itemData here is safe.
         *
         * Doing this without reflection is very difficult.
         */
        itemDef::createItemStack.call(itemData)
}

val ItemStack.itemRepr: ItemRepr
    get() {
        val itemDefId = requireNotNull(getTag(ITEM_DEF_ID_TAG)) { "ItemStack $this has no ItemDefinition." }
        val itemDataId =
            requireNotNull(getTag(ITEM_DATA_ID_TAG)) { "ItemStack $this has no ItemData for ItemDefinition $itemDefId." }
        return ItemRepr(FeatureDefinition.Id(itemDefId), itemDataId)
    }