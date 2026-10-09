import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}

group = "mn.murray"
version = "2.0.0"

repositories {
    mavenCentral()
}

// ---------------------------------------------------------------------------
// Version catalog (kept inline for a single-module build)
// ---------------------------------------------------------------------------
val ktorVersion = "3.1.1"
val exposedVersion = "0.55.0"
val logbackVersion = "1.5.16"
val serializationVersion = "1.7.3"
val hikariVersion = "5.1.0"
val sqliteVersion = "3.46.1.3"
val flywayVersion = "10.20.1"
val jbcryptVersion = "0.4"
val dotenvVersion = "6.4.1"
val junitVersion = "5.10.2"

dependencies {
    // --- Ktor server -------------------------------------------------------
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-compression-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // --- Persistence: Exposed ORM + SQLite + Hikari pool + Flyway ----------
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:$sqliteVersion")
    implementation("com.zaxxer:HikariCP:$hikariVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")

    // --- Serialization -----------------------------------------------------
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // --- Security ----------------------------------------------------------
    implementation("org.mindrot:jbcrypt:$jbcryptVersion")

    // --- Config ------------------------------------------------------------
    implementation("io.github.cdimascio:dotenv-kotlin:$dotenvVersion")

    // --- Logging -----------------------------------------------------------
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // --- Tests -------------------------------------------------------------
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.10")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

application {
    mainClass.set("mn.murray.pts.ApplicationKt")
    // Allow `./gradlew run --args="..."` and forward the data dir if provided.
    applicationDefaultJvmArgs = listOf(
        "-Dfile.encoding=UTF-8",
        "-Duser.timezone=UTC"
    )
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Produce a fat-ish distribution via `./gradlew installDist` for Docker/run scripts.
tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "mn.murray.pts.ApplicationKt"
    }
}
