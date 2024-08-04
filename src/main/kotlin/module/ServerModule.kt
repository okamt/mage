package helio.module

import helio.module.FeatureData.Class
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
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance

private var registeredAllModules = false
private val registeredModules = mutableListOf<ServerModule>()

/**
 * Calls [ServerModule.registerModule] for every builtin [ServerModule] and processes all [FeatureDefinition] annotations in the [ServerModule] package.
 *
 * @throws IllegalStateException if called more than once.
 */
fun registerAllBuiltinModules() {
    check(!registeredAllModules) { "Already registered all modules." }

    ClassGraph().enableClassInfo().enableAnnotationInfo().acceptPackages(ServerModule::class.java.packageName)
        .scan().getSubclasses(ServerModule::class.java).forEach {
            val obj = it.loadClass().kotlin.objectInstance
            require(obj != null) { "ServerModule class ${it.name} should be an object." }
            val serverModule = obj as ServerModule
            serverModule.registerModule()
            registeredModules.add(serverModule)
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
 * @param data The companion object of the [FeatureData] (which should be a [FeatureDataClass]).
 */
sealed class FeatureDefinition<ID_TYPE : Comparable<ID_TYPE>, DATA : FeatureData<ID_TYPE>>(
    val data: FeatureData.Class<ID_TYPE, DATA>
) {
    /**
     * Human-readable ID of a [FeatureDefinition]. Should be unique within its system.
     */
    abstract val id: String

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
sealed class FeatureData<T : Comparable<T>>(id: EntityID<T>) : Entity<T>(id) {
    /**
     * The companion object of a [FeatureData].
     *
     * @param table The [Table] object.
     */
    sealed class Class<ID_TYPE : Comparable<ID_TYPE>, DATA : FeatureData<ID_TYPE>>(
        table: IdTable<ID_TYPE>,
    ) :
        EntityClass<ID_TYPE, DATA>(table) {
    }

    val clazz = this::class.companionObjectInstance as Class<*, *>
}

/**
 * Registry for [FeatureDefinition]s.
 */
sealed interface FeatureRegistry<DEF : FeatureDefinition<*, *>> {
    fun register(definition: DEF)
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