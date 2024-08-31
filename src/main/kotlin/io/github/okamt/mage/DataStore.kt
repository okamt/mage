package io.github.okamt.mage

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * A generic interface for accessing and manipulating data indirectly.
 */
interface DataStore<KEY : Comparable<KEY>, DATA> {
    fun hasData(key: KEY): Boolean
    fun withData(key: KEY, block: DATA.() -> Unit): Boolean
    fun writeData(key: KEY, block: DATA.() -> Unit = {})
    fun makeData(block: DATA.() -> Unit = {}): KEY

    /**
     * An extension to [DataStore], for accessing and manipulating data directly.
     */
    interface Direct<KEY : Comparable<KEY>, DATA> : DataStore<KEY, DATA> {
        fun newData(key: KEY, block: DATA.() -> Unit = {}): DATA
        fun getData(key: KEY): DATA?
        fun setData(key: KEY, data: DATA): DATA?
        fun removeData(key: KEY): DATA?
        fun getDataOrNew(key: KEY, block: DATA.() -> Unit = {}): DATA =
            getData(key) ?: newData(key, block)
    }
}

/**
 * A [DataStore.Direct] that stores only one thing.
 * Both the key and data are immutable, modifying operations are no-op.
 */
abstract class SingleDataStore<KEY : Comparable<KEY>, DATA> : DataStore.Direct<KEY, DATA> {
    companion object {
        fun <KEY : Comparable<KEY>, DATA> make(key: KEY, data: DATA): SingleDataStore<KEY, DATA> =
            object : SingleDataStore<KEY, DATA>() {
                override val singleKey = key
                override val singleData = data
            }
    }

    protected abstract val singleKey: KEY
    protected abstract val singleData: DATA

    override fun hasData(key: KEY): Boolean = true

    override fun withData(key: KEY, block: DATA.() -> Unit): Boolean {
        singleData.block()
        return true
    }

    override fun writeData(key: KEY, block: DATA.() -> Unit) {
        singleData.block()
    }

    override fun makeData(block: DATA.() -> Unit): KEY {
        singleData.block()
        return singleKey
    }

    override fun newData(key: KEY, block: DATA.() -> Unit): DATA =
        singleData

    override fun getData(key: KEY): DATA? =
        singleData

    override fun setData(key: KEY, data: DATA): DATA? =
        singleData

    override fun removeData(key: KEY): DATA? =
        singleData
}

/**
 * A [SingleDataStore] where data is [Unit].
 */
class DummyDataStore<KEY : Comparable<KEY>>(override val singleKey: KEY) : SingleDataStore<KEY, Unit>() {
    override val singleData = Unit
}

/**
 * A [DataStore] that keeps data in memory only (non-persistent). Has methods for accessing [DATA] directly.
 *
 * If [keyMaker] is not supplied, the [makeData] method will always fail.
 *
 * Recommended to be used as the companion object of [DATA] (like [PersistentData.Store]).
 */
open class VolatileDataStore<KEY : Comparable<KEY>, DATA>(
    private val keyMaker: (() -> KEY)? = null,
    private val dataMaker: () -> DATA,
) : DataStore.Direct<KEY, DATA> {
    private val volatileDataMap = mutableMapOf<KEY, DATA>()

    override fun hasData(key: KEY): Boolean = key in volatileDataMap

    override fun withData(key: KEY, block: DATA.() -> Unit): Boolean {
        val data = volatileDataMap[key]
        if (data == null) {
            return false
        } else {
            data.block()
            return true
        }
    }

    override fun writeData(key: KEY, block: DATA.() -> Unit) {
        val data = dataMaker()
        data.block()
        volatileDataMap[key] = data
    }

    override fun makeData(block: DATA.() -> Unit): KEY {
        val key = (keyMaker ?: error("Can't make data on this VolatileDataStore."))()
        writeData(key, block)
        return key
    }

    override fun newData(key: KEY, block: DATA.() -> Unit): DATA {
        val data = dataMaker()
        data.block()
        volatileDataMap[key] = data
        return data
    }

    override fun getData(key: KEY): DATA? =
        volatileDataMap[key]

    override fun setData(key: KEY, data: DATA): DATA? {
        val previous = volatileDataMap[key]
        volatileDataMap[key] = data
        return previous
    }

    override fun removeData(key: KEY): DATA? =
        volatileDataMap.remove(key)
}

/**
 * Data backed by an SQL table. See [PersistentData.Store] for the [DataStore].
 */
abstract class PersistentData<ID_TYPE : Comparable<ID_TYPE>>(id: EntityID<ID_TYPE>) : Entity<ID_TYPE>(id) {
    /**
     * [DataStore] for [PersistentData].
     * If [entityCtor] is null, MUST be the companion object of [DATA].
     *
     * @param table The [IdTable] object.
     */
    open class Store<ID_TYPE : Comparable<ID_TYPE>, DATA : PersistentData<ID_TYPE>>(
        table: IdTable<ID_TYPE>,
        entityCtor: ((EntityID<ID_TYPE>) -> DATA)? = null,
    ) : EntityClass<ID_TYPE, DATA>(table, entityCtor = entityCtor), DataStore<ID_TYPE, DATA> {
        init {
            transaction {
                SchemaUtils.create(table)
            }
        }

        override fun hasData(key: ID_TYPE): Boolean =
            transaction {
                findById(key) != null
            }

        override fun withData(key: ID_TYPE, block: DATA.() -> Unit): Boolean =
            transaction {
                val data = findById(key)
                if (data == null) {
                    false
                } else {
                    data.block()
                    true
                }
            }

        override fun writeData(key: ID_TYPE, block: DATA.() -> Unit) {
            transaction {
                findById(key)?.apply(block) ?: new(key, block)
            }
        }

        override fun makeData(block: DATA.() -> Unit): ID_TYPE =
            transaction {
                new(block).id.value
            }
    }
}
