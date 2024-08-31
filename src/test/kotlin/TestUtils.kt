import org.jetbrains.exposed.sql.Database

/**
 * Makes an in-memory [Database] for testing.
 */
fun makeTestDatabase(): Database = Database.connect(
    "jdbc:h2:mem:regular",
    driver = "org.h2.Driver",
)