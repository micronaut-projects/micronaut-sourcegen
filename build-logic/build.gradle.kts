plugins {
    id("groovy-gradle-plugin")
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.micronaut.gradle.plugin)
    implementation(libs.sonatype.scan)
    implementation(libs.managed.asm.commons)
    implementation(libs.managed.asm.util)
}
