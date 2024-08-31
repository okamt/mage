package io.github.okamt.mage.module

import io.github.classgraph.ClassGraph
import io.github.okamt.mage.util.all
import net.bladehunt.kotstom.GlobalEventHandler
import net.minestom.server.event.EventNode
import org.tinylog.kotlin.Logger
import java.util.*
import kotlin.reflect.full.findAnnotation

private var registeredAllModules = false
private val registeredModules = mutableListOf<ServerModule>()

enum class BuiltinType {
    CORE,
    INTEGRATION,
}

internal annotation class Builtin(val type: BuiltinType)

/**
 * Calls [ServerModule.registerModule] for every builtin [ServerModule] and processes all [FeatureDefinition] annotations in the [ServerModule] package.
 *
 * @throws IllegalStateException if called more than once.
 */
fun registerAllBuiltinModules(filter: EnumSet<BuiltinType> = all()) {
    check(!registeredAllModules) { "Already registered all modules." }

    ClassGraph().enableClassInfo().enableAnnotationInfo().acceptPackages(ServerModule::class.java.packageName)
        .scan().getSubclasses(ServerModule::class.java).forEach {
            val clazz = it.loadClass().kotlin
            val obj = clazz.objectInstance
            require(obj != null) { "ServerModule class ${it.name} should be an object." }
            val builtinAnnotation = clazz.findAnnotation<Builtin>()
            if (builtinAnnotation != null) {
                if (builtinAnnotation.type !in filter) {
                    return@forEach
                }
            }
            registerModule(obj as ServerModule)
        }

    registerAllAnnotatedFeaturesWithFilter(ServerModule::class.java.packageName, filter)

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
 * Defines a module to be loaded by the server at startup.
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
