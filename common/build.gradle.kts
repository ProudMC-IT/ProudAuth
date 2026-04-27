dependencies {
    api(libs.jetbrainsAnnotations)

    compileOnly(libs.hikari)
    compileOnly(libs.jbcrypt)
    compileOnly(libs.totp)
    compileOnly(libs.mysql)
}

tasks.jar {
    enabled = false
}
