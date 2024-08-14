plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("plugin.serialization") version "2.0.0"
}

group = "helio"
version = "0.0.0"

repositories {
    mavenCentral()

    maven("https://mvn.bladehunt.net/releases")
}

val minestomVersion = "f53625f35b"
val kotstomVersion = "0.3.0-alpha.1"
val schemVersion = "1.2.0"
val kotlinScriptingJsr223Version = "2.0.0"
val exposedVersion = "0.52.0"
val kotlinxSerializationVersion = "1.7.1"
val tinylogVersion = "2.7.0"
val h2databaseVersion = "2.3.230"
val classgraphVersion = "4.8.174"

dependencies {
    implementation("net.minestom:minestom-snapshots:$minestomVersion")

    implementation("net.bladehunt:kotstom:$kotstomVersion")
    implementation("net.bladehunt:kotstom-adventure-serialization:$kotstomVersion")

    implementation("dev.hollowcube:schem:$schemVersion")

    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinScriptingJsr223Version")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime")

    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$kotlinxSerializationVersion")

    implementation("org.tinylog:tinylog-api:$tinylogVersion")
    implementation("org.tinylog:tinylog-api-kotlin:$tinylogVersion")
    implementation("org.tinylog:tinylog-impl:$tinylogVersion")
    implementation("org.tinylog:slf4j-tinylog:$tinylogVersion")

    implementation("com.h2database:h2:$h2databaseVersion")

    implementation("io.github.classgraph:classgraph:$classgraphVersion")

    testImplementation(kotlin("test"))
    implementation(kotlin("reflect"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "helio.MainKt"
    }

    // https://stackoverflow.com/a/71094727
    val dependencies = configurations
        .runtimeClasspath
        .get()
        .map(::zipTree)
    from(dependencies)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}