package io.github.okamt.mage.module

import io.github.classgraph.AnnotationClassRef
import io.github.classgraph.AnnotationEnumValue
import io.github.classgraph.ClassGraph
import io.github.okamt.mage.util.all
import org.tinylog.kotlin.Logger
import java.util.EnumSet
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

/**
 * Registers all [FeatureDefinition]s annotated with [RegisterFeature] in package [inPackage].
 */
fun registerAllAnnotatedFeatures(inPackage: String) = registerAllAnnotatedFeaturesWithFilter(inPackage)

internal fun registerAllAnnotatedFeaturesWithFilter(
    inPackage: String,
    filter: EnumSet<BuiltinType> = all(),
) {
    Logger.debug("Registering all features in package $inPackage with filter $filter...")

    ClassGraph().enableClassInfo().enableAnnotationInfo().acceptPackages(inPackage).scan()
        .getClassesWithAnnotation(RegisterFeature::class.java.name).forEach { classInfo ->
            val builtinType =
                classInfo.getAnnotationInfo(Builtin::class.java)?.parameterValues[0]?.value
            if (builtinType != null) {
                if ((builtinType as AnnotationEnumValue).valueName !in filter.map { it.name }) {
                    return@forEach
                }
            }

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
