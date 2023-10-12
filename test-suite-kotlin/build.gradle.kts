plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id("org.jetbrains.kotlin.jvm") version("1.8.22")
    id("com.google.devtools.ksp") version "1.8.22-1.0.11"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.8.22"
}

dependencies {
    ksp(mn.micronaut.inject.kotlin)
    ksp(projects.sourcegenGeneratorKotlin)
    ksp(projects.testSuiteCustomGenerators)
    implementation(mn.micronaut.inject.kotlin)
    implementation(projects.sourcegenAnnotations)
    implementation(projects.testSuiteCustomAnnotations)
    testImplementation(mnTest.micronaut.test.kotest5)
    testRuntimeOnly(mnTest.kotest.runner.junit5.jvm)
}
