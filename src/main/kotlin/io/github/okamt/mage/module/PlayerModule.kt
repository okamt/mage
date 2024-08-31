package io.github.okamt.mage.module

import java.util.*

/**
 * The players module.
 */
@Builtin(BuiltinType.CORE)
object PlayerModule : ServerModule("playerModule") {
    override fun onRegisterModule() {}
}

object PlayerFeatureRegistry : MapFeatureRegistry<FeatureDefinition>()

typealias PlayerDataId = UUID