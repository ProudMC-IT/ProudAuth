dependencies {
    api(libs.jetbrainsAnnotations)
    api(libs.snakeyaml)

    compileOnly(libs.hikari)
    compileOnly(libs.jbcrypt)
    compileOnly(libs.totp)
    compileOnly(libs.mysql)
}

tasks.jar {
    enabled = false
}
