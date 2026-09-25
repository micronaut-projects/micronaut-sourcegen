plugins {
    id("io.micronaut.build.internal.sourcegen-module")
    id("io.micronaut.build.internal.kotlin")}


dependencies {
    implementation(projects.sourcegenGenerator)
    implementation(libs.managed.kotlinpoet)
    implementation(libs.managed.kotlinpoet.javapoet)

    testImplementation(projects.testSuiteCustomGenerators)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mn.kotlin.compiler.embeddable)
    testImplementation(mnTest.junit.jupiter.params)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

tasks.test {
    develocity.predictiveTestSelection.enabled = false
}
