plugins {
    id("io.micronaut.build.internal.sourcegen-lib-module")
}

repositories {
    maven(uri("https://www.jetbrains.com/intellij-repository/releases/"))
}

dependencies {
    api(projects.sourcegenModel)

    implementation(libs.managed.asm)
    implementation(libs.managed.asm.commons)
    implementation(libs.managed.asm.util)

    compileOnly(mn.micronaut.core.processor)

    testImplementation(projects.sourcegenAnnotations)
    testImplementation(projects.sourcegenGenerator)
    testImplementation(mn.micronaut.core.reactive)
    testImplementation(mn.micronaut.inject.java.test)
    testImplementation(mn.micronaut.inject.groovy.test)
    testImplementation(mn.micronaut.core.processor)
    testImplementation(mn.reactor.test)
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(libs.intellij.java.decompiler)
    testImplementation(projects.testSuiteCustomGenerators)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
