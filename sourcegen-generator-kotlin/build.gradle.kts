plugins {
    id("io.micronaut.build.internal.sourcegen-module")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(projects.sourcegenGenerator)
    implementation(libs.managed.kotlinpoet)
    implementation(libs.managed.kotlinpoet.javapoet)
}
