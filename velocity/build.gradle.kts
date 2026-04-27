import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.WriteProperties

fun Provider<MinimalExternalModuleDependency>.coordinate(): String {
    val dependency = get()
    return "${dependency.module.group}:${dependency.module.name}:${dependency.versionConstraint.requiredVersion}"
}

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

the<SourceSetContainer>().named("main") {
    resources.srcDir("../common/src/main/resources")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":bootstrap"))

    compileOnly(libs.velocityApi)
    annotationProcessor(libs.velocityApi)

    implementation(libs.hikari)
    implementation(libs.jbcrypt)
    implementation(libs.totp)
    implementation(libs.mysql)
    implementation(libs.snakeyaml)

    compileOnly(libs.byteBuddy)
    compileOnly(libs.byteBuddyAgent)
}

tasks.register<WriteProperties>("generateVelocityRuntimeLibraryMetadata") {
    destinationFile = generatedVelocityRuntimeMetadata.get().asFile
    encoding = "UTF-8"
    velocityRuntimeLibraries.forEachIndexed { index, coordinate ->
        property("library.${index + 1}", coordinate)
    }
}

tasks.processResources {
    dependsOn(tasks.named("generateVelocityRuntimeLibraryMetadata"))
    from(layout.buildDirectory.dir("generated/proudauth-runtime-resources"))
}

tasks.jar {
    enabled = false
}
