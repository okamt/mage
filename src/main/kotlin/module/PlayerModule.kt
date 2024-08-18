package helio.module

import java.util.*

/**
 * The players module.
 */
@BuiltinModule(BuiltinModuleType.CORE)
object PlayerModule : ServerModule("playerModule") {
    override fun onRegisterModule() {}
}

object PlayerFeatureRegistry : MapFeatureRegistry<FeatureDefinition>()

typealias PlayerDataId = UUID