plugins {
    id("io.micronaut.build.internal.sourcegen-module")
    id("io.micronaut.build.internal.kotlin")}


dependencies {
    implementation(projects.sourcegenGenerator)
    implementation(libs.managed.kotlinpoet)
    implementation(libs.managed.kotlinpoet.javapoet)

    testImplementation(mnTest.micronaut.test.junit5)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

tasks.test {
    develocity.predictiveTestSelection.enabled = false
}
