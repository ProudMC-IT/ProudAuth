import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

fun Provider<MinimalExternalModuleDependency>.coordinate(): String {
    val dependency = get()
    return "${dependency.module.group}:${dependency.module.name}:${dependency.versionConstraint.requiredVersion}"
}

val generatedVelocitySources = layout.buildDirectory.dir("generated/sources/proudauth")
val velocityRuntimeLibraries = listOf(
    libs.hikari.coordinate(),
    libs.jbcrypt.coordinate(),
    libs.totp.coordinate(),
    libs.commonsCodec.coordinate(),
    libs.zxingCore.coordinate(),
    libs.zxingJavase.coordinate(),
    libs.jcommander.coordinate(),
    libs.mysql.coordinate(),
    libs.protobuf.coordinate(),
    libs.snakeyaml.coordinate(),
    libs.byteBuddy.coordinate(),
    libs.byteBuddyAgent.coordinate()
)
val generatedVelocityRuntimeResources = layout.buildDirectory.dir("generated/proudauth-runtime-resources")
val generatedVelocityRuntimeMetadata = generatedVelocityRuntimeResources.map {
    it.file("proudauth-velocity-runtime-libraries.properties")
}
val generatedVelocityPluginResources = layout.buildDirectory.dir("generated/velocity-plugin")
val generatedVelocityPluginMetadata = generatedVelocityPluginResources.map {
    it.file("velocity-plugin.json")
}

dependencies {
    implementation(libs.bstatsBukkit)

    compileOnly(libs.jetbrainsAnnotations)

    compileOnly(libs.paperApi)
    compileOnly(libs.velocityApi)

    compileOnly(libs.hikari)
    compileOnly(libs.jbcrypt)
    compileOnly(libs.totp)
    compileOnly(libs.mysql)
    compileOnly(libs.snakeyaml)
    compileOnly(libs.byteBuddy)
    compileOnly(libs.byteBuddyAgent)
}

the<SourceSetContainer>().named("main") {
    java.setSrcDirs(
        listOf(
            generatedVelocitySources.get().asFile.path,
            "../common/src/main/java",
            "../bootstrap/src/main/java",
            "../bukkit/src/main/java",
            "../velocity/src/main/java"
        )
    )
    resources.setSrcDirs(
        listOf(
            "../common/src/main/resources",
            "../bukkit/src/main/resources",
            "../velocity/src/main/resources"
        )
    )
}

the<SourceSetContainer>().named("test") {
    java.setSrcDirs(emptyList<String>())
    resources.setSrcDirs(emptyList<String>())
}

tasks.register<WriteProperties>("generateVelocityRuntimeLibraryMetadata") {
    destinationFile = generatedVelocityRuntimeMetadata.get().asFile
    encoding = "UTF-8"
    velocityRuntimeLibraries.forEachIndexed { index, coordinate ->
        property("library.${index + 1}", coordinate)
    }
}

val generateVelocityBuildConstants = tasks.register("generateVelocityBuildConstants") {
    val outputDirectory = generatedVelocitySources
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    outputs.dir(outputDirectory)

    doLast {
        val packagePath = outputDirectory.get().dir("com/monkey/proudAuth/velocity").asFile
        packagePath.mkdirs()
        packagePath.resolve("ProudAuthBuildConstants.java").writeText(
            """
            package com.monkey.proudAuth.velocity;

            public final class ProudAuthBuildConstants {
                public static final String VERSION = "$pluginVersion";

                private ProudAuthBuildConstants() {
                }
            }
            """.trimIndent() + System.lineSeparator(),
            Charsets.UTF_8
        )
    }
}

val generateVelocityPluginJson = tasks.register("generateVelocityPluginJson") {
    val pluginVersion = project.version.toString()
    val outputFile = generatedVelocityPluginMetadata
    inputs.property("version", pluginVersion)
    outputs.file(outputFile)

    doLast {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {"id":"proudauth","name":"ProudAuth","version":"$pluginVersion","description":"Proxy companion per ProudAuth","authors":["MonkeyMoon104"],"dependencies":[],"main":"com.monkey.proudAuth.velocity.ProudAuthVelocityPlugin"}
            """.trimIndent() + System.lineSeparator(),
            Charsets.UTF_8
        )
    }
}

tasks.compileJava {
    dependsOn(generateVelocityBuildConstants)
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    inputs.property("version", pluginVersion)
    dependsOn(tasks.named("generateVelocityRuntimeLibraryMetadata"))
    dependsOn(generateVelocityPluginJson)
    filesMatching("plugin.yml") {
        expand(mapOf("version" to pluginVersion))
    }
    from(layout.buildDirectory.dir("generated/proudauth-runtime-resources"))
    from(layout.buildDirectory.dir("generated/velocity-plugin"))
}

tasks.jar {
    enabled = false
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("ProudAuth.jar")
    relocate("org.bstats", "${project.group}.libs.bstats")
}

tasks.assemble {
    dependsOn(tasks.named("shadowJar"))
}
