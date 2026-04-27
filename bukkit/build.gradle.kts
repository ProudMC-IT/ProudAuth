import org.gradle.api.tasks.SourceSetContainer

the<SourceSetContainer>().named("main") {
    resources.srcDir("../common/src/main/resources")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":bootstrap"))

    compileOnly(libs.paperApi)
    compileOnly(libs.bstatsBukkit)
    compileOnly(libs.hikari)
    compileOnly(libs.jbcrypt)
    compileOnly(libs.totp)
    compileOnly(libs.mysql)
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand(mapOf("version" to pluginVersion))
    }
}

tasks.jar {
    enabled = false
}
