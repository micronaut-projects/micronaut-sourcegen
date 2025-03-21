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
    implementation(libs.managed.asm)
    implementation(libs.managed.asm.commons)
    implementation(libs.managed.asm.util)

    compileOnly(mn.micronaut.core.processor)

    testImplementation(projects.sourcegenAnnotations)
    testImplementation(projects.sourcegenGenerator)
    testImplementation(mn.micronaut.inject.java.test)
    testImplementation(mn.micronaut.core.processor)
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(libs.intellij.java.decompiler)
    testImplementation(projects.testSuiteCustomGenerators)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
