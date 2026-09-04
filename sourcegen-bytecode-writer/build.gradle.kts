plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

repositories {
    maven(
        url = uri("https://www.jetbrains.com/intellij-repository/releases/")
    )
}

dependencies {
    api(projects.sourcegenModel)
    api(projects.sourcegenBytecodeWriterCore)
    implementation(libs.managed.asm)
    implementation(libs.managed.asm.commons)
    implementation(libs.managed.asm.util)

    compileOnly(mn.micronaut.core.processor) {
        exclude("io.micronaut.sourcegen")
    }

    testImplementation(projects.sourcegenBytecodeWriterTck)
    testImplementation(projects.sourcegenAnnotations)
    testImplementation(projects.sourcegenGenerator)
    testImplementation(mn.micronaut.core.reactive)
    testImplementation(mn.micronaut.inject.java.test)
    testImplementation(mn.micronaut.inject.groovy.test)
    testImplementation(mn.micronaut.core.processor) {
        exclude("io.micronaut.sourcegen")
    }
    testImplementation(mn.reactor.test)
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(libs.intellij.java.decompiler)
    testImplementation(projects.testSuiteCustomGenerators)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
