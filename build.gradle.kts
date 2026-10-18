import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("io.github.drownek.plugwright") version "2.0.4"
    jacoco
}

group = "ru.ruscrafting"
version = "0.5.23"
description = "Cross-server custom events for RusCrafting"

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") { content { includeGroup("ru.ruscrafting.arc") } }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.codemc.io/repository/maven-public/")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

val worldEditVersion = "7.3.18"
val arcCoreVersion = "2.7.41"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-menu:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-redis:$arcCoreVersion")
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:$arcCoreVersion")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:$worldEditVersion")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.16")
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1") { exclude(group = "io.netty") }
    compileOnly("net.luckperms:api:5.5")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("com.sk89q.worldedit:worldedit-core:$worldEditVersion")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:$arcCoreVersion")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:$arcCoreVersion")
    testImplementation("net.luckperms:api:5.5")
    testImplementation("com.github.retrooper:packetevents-spigot:2.12.1") { exclude(group = "io.netty") }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:$arcCoreVersion")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
}

tasks {
    withType<Test>().configureEach {
        // MockK/ByteBuddy must attach inside the forked JVM on JDK 25. Without this,
        // the external helper can hang and leave an orphan Gradle Test Executor.
        jvmArgs("-Djdk.attach.allowAttachSelf=true")
    }
    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    processResources {
        inputs.property("version", project.version)
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test {
        useJUnitPlatform()
        testLogging {
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        providers.gradleProperty("ruscraftingOpsRoot")
            .orElse(providers.environmentVariable("RUSCRAFTING_OPS_ROOT"))
            .orNull
            ?.let { systemProperty("ruscrafting.opsRoot", it) }
    }
    register<Test>("integrationTest") {
        description = "Runs disposable cross-node Redis integration tests."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(test)
    }
    jar { archiveClassifier.set("plain") }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("org/slf4j/**")
        exclude("org/bukkit/**")
        exclude("io/papermc/**")
        exclude("net/kyori/adventure/**")
        exclude("ru/arc/paper/sql/PaperSqlServiceProvider*.class")
        exclude("ru/arc/paper/sql/SharedPaperSqlService*.class")
        exclude("ru/arc/paper/redis/PaperRedisServiceProvider*.class")
        exclude("ru/arc/paper/redis/SharedPaperRedisService*.class")
        exclude("ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
        exclude("ru/arc/paper/runtime/PaperNativeCleanupServiceProvider*.class")
        exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
        exclude("ru/arc/paper/api/**")
        // ARC is the sole native sidebar host; consumers share only its API class identity.
        exclude("ru/arc/paper/sidebar/**")
    }
    check { dependsOn(shadowJar, "integrationTest") }
}

dependencyLocking { lockAllConfigurations() }

// Real Paper tests need the shared ARC plugin, not only its compile-time API.
// CI supplies a matching ARC shadow jar; local unit/integration tasks remain
// independent of an ARC checkout.
val e2eArcJar = providers.gradleProperty("e2eArcJar").map { file(it) }
val e2eArcCoreJar = providers.gradleProperty("e2eArcCoreJar").map { file(it) }
val verifyE2eProvider = tasks.register("verifyE2eProvider") {
    description = "Verifies the standalone provider used by the isolated Paper fixture."
    inputs.file(e2eArcCoreJar).optional()
    doLast {
        val provider = requireNotNull(e2eArcCoreJar.orNull) {
            "Supply -Pe2eArcCoreJar=/absolute/path/ArcCore-0.1.12.jar for the real Paper fixture"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(provider.readBytes()).joinToString("") { "%02x".format(it) }
        require(digest == "93158072f30ad3d7d5201acdf9036324841c4455844b4f1b650cfea0d8540502") {
            "ArcCore 0.1.12 fixture checksum mismatch: $digest"
        }
    }
}
tasks.withType<me.drownek.plugwright.AbstractPlugwrightTask>().configureEach {
    dependsOn(verifyE2eProvider)
}

plugwright {
    minecraftVersion.set("1.21.11")
    val hostE2e = providers.environmentVariable("ARC_EVENTS_E2E_HOST").orNull == "1"
    runDir.set(layout.buildDirectory.dir(if (hostE2e) "plugwright-host" else "plugwright"))
    testsDir.set(layout.projectDirectory.dir(if (hostE2e) "src/test/e2e-host" else "src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(
        listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2") +
            if (hostE2e) listOf("-Dterminal.jline=false", "-Djna.nounpack=true") else emptyList(),
    )
    downloadPlugins {
        url("https://github.com/retrooper/packetevents/releases/download/v2.12.1/packetevents-spigot-2.12.1.jar")
        url("https://cdn.modrinth.com/data/Vebnzrzj/versions/OrIs0S6b/LuckPerms-Bukkit-5.5.17.jar")
        url("https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar")
        url("https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/thirdparty/rediseconomy/4.5.12/rediseconomy-4.5.12.jar")
    }
    writeFiles {
        e2eArcJar.orNull?.let { file("plugins/ARC.jar", it) }
        e2eArcCoreJar.orNull?.let { file("plugins/ArcCore.jar", it) }
        file("plugins/ARC/modules/runtime.yml", projectDir.resolve("src/test/e2e/fixtures/arc-runtime.yml"))
        file("plugins/ARC/modules/ops-http.yml", projectDir.resolve("src/test/e2e/fixtures/arc-ops-http.yml"))
        file("plugins/RedisEconomy/config.yml", projectDir.resolve("src/test/e2e/fixtures/rediseconomy.yml"))
        file("plugins/ARC/modules/misc.yml", projectDir.resolve("src/test/e2e/fixtures/arc-misc.yml"))
        file("plugins/ARC/modules/redis.yml", projectDir.resolve("src/test/e2e/fixtures/arc-redis.yml"))
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file(
            "plugins/ArcEvents/modules/redis.yml",
            projectDir.resolve(if (hostE2e) "src/test/e2e/fixtures/host-redis.yml" else "src/test/e2e/fixtures/redis.yml"),
        )
        if (hostE2e) file("plugins/ArcEvents/config.yml", projectDir.resolve("src/test/e2e/fixtures/host-config.yml"))
    }
}
