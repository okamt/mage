package helio.module

import io.github.classgraph.AnnotationClassRef
import io.github.classgraph.ClassGraph
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.tinylog.kotlin.Logger
import kotlin.reflect.KClass

/**
 * Definition of a feature of a system.
 */
abstract class FeatureDefinition {
    @JvmInline
    value class Id(val value: String) {
        companion object {
            /**
             * Max length of a [FeatureDefinition.id]. For database.
             */
            const val MAX_LEN = 50
        }

        init {
            require(value.length <= MAX_LEN) { "FeatureDefinition id $value must be $MAX_LEN characters long or less." }
        }
    }

    /**
     * Human-readable ID of a [FeatureDefinition]. Should be unique within its system.
     */
    abstract val id: Id

    internal open fun onRegisterDefinition() {}
}

/**
 * A generic interface for accessing and manipulating data indirectly.
 */
interface DataStore<KEY : Comparable<KEY>, DATA> {
    fun hasData(key: KEY): Boolean
    fun withData(key: KEY, block: DATA.() -> Unit): Boolean
    fun writeData(key: KEY, block: DATA.() -> Unit = {})
    fun makeData(block: DATA.() -> Unit = {}): KEY

    interface Direct<KEY : Comparable<KEY>, DATA> : DataStore<KEY, DATA> {
        fun newData(key: KEY, block: DATA.() -> Unit = {}): DATA
        fun getData(key: KEY): DATA?
        fun getDataOrNew(key: KEY, block: DATA.() -> Unit = {}): DATA =
            getData(key) ?: newData(key, block)
    }
}

/**
 * A [DataStore.Direct] that stores only one thing.
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
}

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
abstract class VolatileDataStore<KEY : Comparable<KEY>, DATA>(
    private val dataMaker: () -> DATA,
    private val keyMaker: (() -> KEY)? = null,
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
}

/**
 * Data backed by an SQL table. See [PersistentData.Store] for the [DataStore].
 */
abstract class PersistentData<ID_TYPE : Comparable<ID_TYPE>>(id: EntityID<ID_TYPE>) : Entity<ID_TYPE>(id) {
    /**
     * [DataStore] for [PersistentData].
     *
     * @param table The [IdTable] object.
     */
    open class Store<ID_TYPE : Comparable<ID_TYPE>, DATA : PersistentData<ID_TYPE>>(
        table: IdTable<ID_TYPE>,
    ) : EntityClass<ID_TYPE, DATA>(table), DataStore<ID_TYPE, DATA> {
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

private val defIdsRegistered = mutableSetOf<FeatureDefinition.Id>()

/**
 * Registry for [FeatureDefinition]s.
 */
interface FeatureRegistry<DEF : FeatureDefinition> {
    fun onRegister(definition: DEF)
    fun getDefinition(id: FeatureDefinition.Id): DEF?
}

/**
 * A [FeatureRegistry] that stores [FeatureDefinition]s in a [MutableMap].
 */
open class MapFeatureRegistry<DEF : FeatureDefinition> : FeatureRegistry<DEF> {
    open val definitionMap = mutableMapOf<FeatureDefinition.Id, DEF>()

    override fun onRegister(definition: DEF) {
        definitionMap[definition.id] = definition
    }

    override fun getDefinition(id: FeatureDefinition.Id): DEF? =
        definitionMap[id]
}

fun <DEF : FeatureDefinition> FeatureRegistry<DEF>.register(definition: DEF) {
    Logger.debug("Registering FeatureDefinition ${definition.id} for registry ${this::class.qualifiedName}...")
    require(definition.id !in defIdsRegistered) { "Already registered a FeatureDefinition with id ${definition.id}." }
    defIdsRegistered.add(definition.id)
    definition.onRegisterDefinition()
    onRegister(definition)
}

/**
 * Annotation for automatically registering a [FeatureDefinition] object into a [registry].
 *
 * @param registry A [KClass] for a [FeatureRegistry].
 */
@Target(AnnotationTarget.CLASS)
annotation class RegisterFeature(val registry: KClass<out FeatureRegistry<*>>)

fun registerAllAnnotatedFeatures(
    inPackage: String,
) {
    Logger.debug("Registering all features in package $inPackage...")

    ClassGraph().enableClassInfo().enableAnnotationInfo().acceptPackages(inPackage).scan()
        .getClassesWithAnnotation(RegisterFeature::class.java.name).forEach { classInfo ->
            val def = requireNotNull(classInfo.loadClass().kotlin.objectInstance as? FeatureDefinition)
            { "Class ${classInfo.name} annotated with @RegisterFeature should be an object inheriting FeatureDefinition." }
            val registryClass =
                classInfo.getAnnotationInfo(RegisterFeature::class.java).parameterValues[0].value as AnnotationClassRef

            @Suppress("UNCHECKED_CAST")
            val registry =
                requireNotNull(
                    requireNotNull(registryClass.loadClass().kotlin.objectInstance) { "Registry ${registryClass.name} must be an object." }
                            as? FeatureRegistry<FeatureDefinition>
                ) { "Class argument of @RegisterFeature (${registryClass.name}) in ${classInfo.name} must be a FeatureRegistry." }

            Logger.debug("Registering class ${classInfo.name} for registry ${registry::class.qualifiedName}...")
            registry.register(def)
        }
}
