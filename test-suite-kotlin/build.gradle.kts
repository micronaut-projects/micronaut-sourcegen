plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id("org.jetbrains.kotlin.jvm") version("1.9.10")
    id("com.google.devtools.ksp") version "1.9.10-1.0.13"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.9.10"
}

dependencies {
    ksp(mn.micronaut.inject.kotlin)
    ksp(projects.sourcegenGeneratorKotlin)
    ksp(projects.testSuiteCustomGenerators)
    implementation(mn.micronaut.inject.kotlin)
    implementation(projects.sourcegenAnnotations)
    implementation(projects.testSuiteCustomAnnotations)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
}
