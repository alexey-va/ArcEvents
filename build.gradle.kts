plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("io.github.drownek.plugwright") version "2.0.4"
    jacoco
}

group = "ru.ruscrafting"
version = "0.3.5"
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
    maven("https://repo.dmulloy2.net/repository/public/")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

val arcCoreVersion = "2.7.6"
val worldEditVersion = "7.3.18"

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
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    compileOnly("net.luckperms:api:5.5")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("com.sk89q.worldedit:worldedit-core:$worldEditVersion")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:$arcCoreVersion")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:$arcCoreVersion")
    testImplementation("net.luckperms:api:5.5")
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
    }
    check { dependsOn(shadowJar, "integrationTest") }
}

dependencyLocking { lockAllConfigurations() }

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
        url("https://cdn.modrinth.com/data/Vebnzrzj/versions/OrIs0S6b/LuckPerms-Bukkit-5.5.17.jar")
    }
    writeFiles {
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file(
            "plugins/ArcEvents/modules/redis.yml",
            projectDir.resolve(if (hostE2e) "src/test/e2e/fixtures/host-redis.yml" else "src/test/e2e/fixtures/redis.yml"),
        )
        if (hostE2e) file("plugins/ArcEvents/config.yml", projectDir.resolve("src/test/e2e/fixtures/host-config.yml"))
    }
}
