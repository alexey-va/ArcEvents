plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
}

group = "ru.ruscrafting"
version = "0.1.5"
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

val arcCoreVersion = "2.2.2"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-paper:$arcCoreVersion")
    implementation("ru.ruscrafting.arc:arc-core-redis:$arcCoreVersion")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.16")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:$arcCoreVersion")
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
