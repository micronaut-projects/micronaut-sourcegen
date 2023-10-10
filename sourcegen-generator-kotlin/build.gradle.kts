plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    implementation(projects.sourcegenGenerator)
    implementation(libs.managed.kotlinpoet)
    implementation(libs.managed.kotlinpoet.javapoet)
}
