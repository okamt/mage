package helio.game

import java.io.FileReader
import javax.script.ScriptContext
import javax.script.ScriptEngineManager

val config by lazy {
    val config = Config()

    with(ScriptEngineManager().getEngineByExtension("kts")) {
        val bindings = createBindings()
        bindings["config"] = config
        setBindings(bindings, ScriptContext.ENGINE_SCOPE)

        eval(FileReader("config.kts"))
    }

    config
}

data class Config(
    var address: String = "0.0.0.0",
    var port: Int = 25565,

    // In memory database / keep alive between connections/transactions
    var databaseURL: String = "jdbc:h2:mem:regular;DB_CLOSE_DELAY=-1;",
    var databaseDriver: String = "org.h2.Driver",
)
