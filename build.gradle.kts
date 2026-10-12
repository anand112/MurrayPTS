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
val mailApiVersion = "2.1.3"
val angusMailVersion = "2.0.3"
val junitVersion = "5.10.2"

dependencies {
    // --- Ktor server -------------------------------------------------------
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-html-builder-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-forwarded-header-jvm:$ktorVersion")
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

    // --- Scheduling + outbound email (due-date alerts) ----------------------
    // The scheduler coroutine needs coroutines explicitly (it was only transitively present
    // through Ktor before). Jakarta Mail is the API; Eclipse Angus is the SMTP transport that
    // actually implements it — declaring just the API leaves MailSession without a provider.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("jakarta.mail:jakarta.mail-api:$mailApiVersion")
    implementation("org.eclipse.angus:angus-mail:$angusMailVersion")

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

// ---------------------------------------------------------------------------
// Packaging
//
// The deployable artifact is `./gradlew installDist`, which produces
//   build/install/murray-pts/bin/murray-pts       (POSIX launcher)
//   build/install/murray-pts/bin/murray-pts.bat   (Windows launcher — see below)
//   build/install/murray-pts/lib/*.jar            (app jar + every runtime dependency)
// The launchers already carry `applicationDefaultJvmArgs` above. deploy/run.sh and the
// Dockerfile use the POSIX launcher; deploy/run.bat does NOT use the .bat one, because
// Gradle expands ~70 jars into a single `set CLASSPATH=` line and cmd's 8191-character
// limit rejects it ("The input line is too long"). run.bat starts the same JVM with a
// wildcard classpath (`-cp "<lib>\*"`) instead.
//
// The bare jar below only sets Main-Class so `java -cp <deps> mn.murray.pts.ApplicationKt`
// and IDE run configs work. There is no shadow/fat jar: merging 60 dependency jars would buy
// nothing over installDist and would break the SQLite native driver packaging.
// ---------------------------------------------------------------------------
tasks.named<Jar>("jar") {
    manifest {
        attributes["Main-Class"] = "mn.murray.pts.ApplicationKt"
    }
}
