package helio.module

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.component.DataComponentMap
import net.minestom.server.event.Event
import net.minestom.server.event.item.*
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerItemAnimationEvent
import net.minestom.server.event.player.PlayerUseItemEvent
import net.minestom.server.event.trait.ItemEvent
import net.minestom.server.event.trait.PlayerInstanceEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

val ITEM_DEF_ID_TAG = Tag.String("itemId")
val ITEM_DATA_ID_TAG = Tag.Integer("itemDataId")

/**
 * The items module.
 *
 * Handles delegating events to the appropriate [ItemDefinition]s.
 */
@BuiltinModule(BuiltinModuleType.CORE)
object Items : ServerModule("items"), FeatureRegistry<ItemDefinition<*>> {
    private val map: MutableMap<FeatureDefinition.Id, ItemDefinition<*>> = mutableMapOf()

    private val events = listOf(
        EntityEquipEvent::class,
        ItemDropEvent::class,
        ItemUpdateStateEvent::class,
        ItemUsageCompleteEvent::class,
        PickupExperienceEvent::class,
        PickupItemEvent::class,

        PlayerUseItemEvent::class,
        PlayerBlockPlaceEvent::class,
        PlayerItemAnimationEvent::class,
    )

    private fun getItemStackFromEvent(event: Event): ItemStack = when (event) {
        is PlayerInstanceEvent -> event.player.itemInMainHand
        is ItemEvent -> event.itemStack
        else -> error("Could not get ItemStack from event ${event::class.qualifiedName}.")
    }

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
        for (event in events) {
            eventNode.addListener(event.java) {
                val itemStack = getItemStackFromEvent(it)
                itemStack.definition.delegateEvent(it, itemStack)
            }
        }
    }
}

typealias ItemDataId = Int

abstract class ItemData(id: EntityID<ItemDataId>) : FeatureData<ItemDataId>(id) {
    abstract class Class<DATA : ItemData>(
        table: IdTable<ItemDataId>,
    ) : FeatureData.Class<ItemDataId, DATA>(table)

    class Empty(id: EntityID<ItemDataId>) : ItemData(id) {
        companion object : Class<Empty>(EmptyTable)

        object EmptyTable : IntIdTable("emptyItemData")
    }
}

abstract class ItemDefinition<DATA : ItemData>(
    data: ItemData.Class<DATA>,
) : FeatureDefinition<ItemDataId, DATA>(data) {
    abstract val material: Material

    internal fun delegateEvent(event: Event, itemStack: ItemStack) = when (event) {
        is EntityEquipEvent -> event.handle(getData(itemStack))
        is ItemDropEvent -> event.handle(getData(itemStack))
        is ItemUpdateStateEvent -> event.handle(getData(itemStack))
        is ItemUsageCompleteEvent -> event.handle(getData(itemStack))
        is PickupExperienceEvent -> event.handle(getData(itemStack))
        is PickupItemEvent -> event.handle(getData(itemStack))

        is PlayerUseItemEvent -> event.handle(getData(itemStack))
        is PlayerBlockPlaceEvent -> event.handle(getData(itemStack))
        is PlayerItemAnimationEvent -> event.handle(getData(itemStack))

        else -> error("No event handler for event ${event::class}.")
    }

    open fun EntityEquipEvent.handle(data: DATA) {}
    open fun ItemDropEvent.handle(data: DATA) {}
    open fun ItemUpdateStateEvent.handle(data: DATA) {}
    open fun ItemUsageCompleteEvent.handle(data: DATA) {}
    open fun PickupExperienceEvent.handle(data: DATA) {}
    open fun PickupItemEvent.handle(data: DATA) {}

    open fun PlayerUseItemEvent.handle(data: DATA) {}
    open fun PlayerBlockPlaceEvent.handle(data: DATA) {
        isCancelled = true
    }

    open fun PlayerItemAnimationEvent.handle(data: DATA) {}

    // TODO: all events

    open fun onCreateItemStack(itemStack: ItemStack, itemData: DATA): ItemStack = itemStack

    fun getData(itemStack: ItemStack): DATA {
        val itemDataId = itemStack.getTag(ITEM_DATA_ID_TAG)
        return getData(itemDataId)
            ?: error("ItemStack $itemStack has no associated ItemData for ItemDefinition $id.")
    }

    @Suppress("UnstableApiUsage")
    fun createItemStack(withData: DATA? = null): ItemStack =
        transaction {
            val itemData = withData ?: data.new {}

            val itemStack = ItemStack
                .of(material, DataComponentMap.EMPTY)
                .withCustomName(
                    Component.translatable(this@ItemDefinition.id.value).decoration(TextDecoration.ITALIC, false)
                )
                .withTag(ITEM_DEF_ID_TAG, this@ItemDefinition.id.value)
                .withTag(ITEM_DATA_ID_TAG, itemData.id.value)

            onCreateItemStack(itemStack, itemData)
        }
}

val ItemStack.definition: ItemDefinition<*>
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