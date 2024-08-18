package helio.module

import net.bladehunt.kotstom.dsl.item.item
import net.bladehunt.kotstom.dsl.item.itemName
import net.kyori.adventure.text.Component
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerItemAnimationEvent
import net.minestom.server.event.trait.ItemEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag

val ITEM_DEF_ID_TAG = Tag.String("itemId")
val ITEM_DATA_ID_TAG = Tag.Integer("itemDataId")

/**
 * The items module.
 */
@BuiltinModule(BuiltinModuleType.CORE)
object ItemModule : ServerModule("itemModule") {
    override fun onRegisterModule() {}
}

object ItemRegistry : MapFeatureRegistry<ItemDefinition<*>>() {
    fun getDefinition(itemStack: ItemStack): ItemDefinition<*> {
        val itemDefId =
            FeatureDefinition.Id(requireNotNull(itemStack.getTag(ITEM_DEF_ID_TAG)) { "ItemStack $itemStack has no ItemDefinition." })
        return requireNotNull(getDefinition(itemDefId)) { "ItemStack $itemStack has invalid ItemDefinition id $itemDefId." }
    }

    fun getDefinitionOrNull(itemStack: ItemStack): ItemDefinition<*>? =
        getDefinition(FeatureDefinition.Id(itemStack.getTag(ITEM_DEF_ID_TAG)))
}

typealias ItemDataId = Int

abstract class ItemDefinitionWithoutData : ItemDefinition<Unit>(DummyDataStore(0))

abstract class ItemDefinition<DATA>(
    dataStore: DataStore<ItemDataId, DATA>,
) : FeatureDefinition(), DataStore<ItemDataId, DATA> by dataStore {
    abstract val material: Material

    open val events by lazy { events {} }

    protected fun events(block: MultiEventHandler<ItemDataId>.() -> Unit): MultiEventHandler<ItemDataId> =
        MultiEventHandler<ItemDataId>(id.value)
            .apply {
                dataFor<ItemEvent> { itemStack.dataId }
                dataFor<PlayerBlockPlaceEvent> { player.getItemInHand(hand).dataId }
                dataFor<PlayerItemAnimationEvent> { player.getItemInHand(hand).dataId }
            }
            .apply(block)
            .apply {
                default<PlayerBlockPlaceEvent> {
                    isCancelled = true
                }
            }

    override fun onRegisterDefinition() {
        ItemModule.eventNode.addChild(events.eventNode)
    }

    open fun ItemStack.Builder.onCreateItemStack(withDataId: ItemDataId) {}

    fun createItemStack(withDataId: ItemDataId? = null): ItemStack {
        val itemDataId = withDataId ?: makeData {}

        val itemStack = item(material) {
            itemName = Component.translatable(this@ItemDefinition.id.value)

            set(ITEM_DEF_ID_TAG, this@ItemDefinition.id.value)
            set(ITEM_DATA_ID_TAG, itemDataId)

            onCreateItemStack(itemDataId)
        }

        return itemStack
    }
}

val ItemStack.definition: ItemDefinition<*>
    get() = ItemRegistry.getDefinition(this)

val ItemStack.dataId: ItemDataId
    get() = requireNotNull(getTag(ITEM_DATA_ID_TAG)) { "ItemStack $this has no ItemData." }

/**
 * Small representation of an [ItemStack].
 */
data class ItemRepr(
    val defId: FeatureDefinition.Id,
    val dataId: ItemDataId,
) {
    val definition by lazy {
        requireNotNull(ItemRegistry.getDefinition(defId)) { "ItemRepr has invalid ItemDefinition id $defId." }
    }

    fun createItemStack(): ItemStack =
        definition.createItemStack(dataId)
}

val ItemStack.repr: ItemRepr
    get() = ItemRepr(definition.id, dataId)