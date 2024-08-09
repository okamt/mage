package helio.module

import helio.module.FeatureData.Class
import helio.util.enumSetOfAll
import io.github.classgraph.AnnotationClassRef
import io.github.classgraph.ClassGraph
import net.bladehunt.kotstom.GlobalEventHandler
import net.minestom.server.event.EventNode
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.tinylog.kotlin.Logger
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.findAnnotation

private var registeredAllModules = false
private val registeredModules = mutableListOf<ServerModule>()

enum class BuiltinModuleType {
    CORE,
    INTEGRATION,
}

annotation class BuiltinModule(val type: BuiltinModuleType)

/**
 * Calls [ServerModule.registerModule] for every builtin [ServerModule] and processes all [FeatureDefinition] annotations in the [ServerModule] package.
 *
 * @throws IllegalStateException if called more than once.
 */
fun registerAllBuiltinModules(filter: EnumSet<BuiltinModuleType> = enumSetOfAll()) {
    check(!registeredAllModules) { "Already registered all modules." }

    ClassGraph().enableClassInfo().enableAnnotationInfo().acceptPackages(ServerModule::class.java.packageName)
        .scan().getSubclasses(ServerModule::class.java).forEach {
            val clazz = it.loadClass().kotlin
            val obj = clazz.objectInstance
            require(obj != null) { "ServerModule class ${it.name} should be an object." }
            val builtinModuleAnnotation = clazz.findAnnotation<BuiltinModule>()
            if (builtinModuleAnnotation != null) {
                if (builtinModuleAnnotation.type !in filter) {
                    return@forEach
                }
            }
            registerModule(obj as ServerModule)
        }

    registerAllAnnotatedFeatures(ServerModule::class.java.packageName)

    registeredAllModules = true
}

/**
 * Registers the given [serverModule].
 *
 * @throws IllegalStateException if [serverModule] is already registered.
 */
fun registerModule(serverModule: ServerModule) {
    check(serverModule !in registeredModules) { "Already registered module $serverModule." }
    serverModule.registerModule()
    registeredModules.add(serverModule)
}

/**
 * Defines a module to be loaded by the server at startup. All subclasses will have their [registerModule] method automatically called at server startup.
 */
abstract class ServerModule(val id: String) {
    open val eventNode = EventNode.all(id)
    internal fun registerModule() {
        Logger.info("Registering module ${this::class.qualifiedName} (id $id)...")
        GlobalEventHandler.addChild(eventNode)
        onRegisterModule()
    }

    abstract fun onRegisterModule()
}

/**
 * Definition of a feature of a system.
 *
 * @param data The companion object of the [FeatureData] (which should be a [FeatureData.Class]).
 */
abstract class FeatureDefinition<ID_TYPE : Comparable<ID_TYPE>, DATA : FeatureData<ID_TYPE>>(
    val data: Class<ID_TYPE, DATA>
) {
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

    fun getDataOrNew(
        id: ID_TYPE,
        init: DATA.() -> Unit = {}
    ): DATA =
        transaction {
            data.findById(id) ?: data.new(id, init)
        }

    fun getData(id: ID_TYPE): DATA? =
        transaction {
            data.findById(id)
        }

    fun writeData(id: ID_TYPE, block: DATA.() -> Unit) =
        transaction {
            data.findById(id)?.apply(block) ?: data.new(id, block)
        }
}

/**
 * Data for a feature of a system. Each [FeatureData] has its own Exposed [Table]. All subclasses must have a companion object that extends [Class].
 */
abstract class FeatureData<T : Comparable<T>>(id: EntityID<T>) : Entity<T>(id) {
    /**
     * The companion object of a [FeatureData].
     *
     * @param table The [Table] object.
     */
    open class Class<ID_TYPE : Comparable<ID_TYPE>, DATA : FeatureData<ID_TYPE>>(
        table: IdTable<ID_TYPE>,
    ) :
        EntityClass<ID_TYPE, DATA>(table) {
    }

    val clazz = this::class.companionObjectInstance as Class<*, *>
}

val defIdsRegistered = mutableSetOf<FeatureDefinition.Id>()

/**
 * Registry for [FeatureDefinition]s.
 */
interface FeatureRegistry<DEF : FeatureDefinition<*, *>> {
    fun onRegister(definition: DEF)
}

fun <DEF : FeatureDefinition<*, *>> FeatureRegistry<DEF>.register(definition: DEF) {
    require(definition.id !in defIdsRegistered) { "Already registered a FeatureDefinition with id ${definition.id}." }
    defIdsRegistered.add(definition.id)
    onRegister(definition)
}

/**
 * Annotation for automatically registering a [FeatureDefinition] object into a [registry].
 *
 * @param registry A [KClass] for a [FeatureRegistry].
 */
@Target(AnnotationTarget.CLASS)
annotation class RegisterFeature(val registry: KClass<*>)

fun registerAllAnnotatedFeatures(
    inPackage: String,
) {
    Logger.debug("Registering all features in package $inPackage...")

    ClassGraph().enableClassInfo().enableAnnotationInfo().acceptPackages(inPackage).scan()
        .getClassesWithAnnotation(RegisterFeature::class.java.name).forEach { classInfo ->
            val def = classInfo.loadClass().kotlin.objectInstance as? FeatureDefinition<*, *>
                ?: throw Exception("Class ${classInfo.name} annotated with @RegisterFeature should be an object inheriting FeatureDefinition.")
            val registryClass =
                classInfo.getAnnotationInfo(RegisterFeature::class.java).parameterValues[0].value as AnnotationClassRef

            @Suppress("UNCHECKED_CAST")
            val registry =
                (registryClass.loadClass().kotlin.objectInstance
                    ?: throw Exception("Registry ${registryClass.name} must be an object."))
                        as? FeatureRegistry<FeatureDefinition<*, *>>
                    ?: throw Exception("Class argument of @RegisterFeature (${registryClass.name}) must be a FeatureRegistry.")

            Logger.debug("Registering class ${classInfo.name} for registry ${registry::class.qualifiedName}...")
            registry.register(def)
        }
}