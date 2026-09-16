plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id("io.micronaut.build.internal.kotlin-ksp")

}

dependencies {
    ksp(mn.micronaut.inject.kotlin)
    ksp(projects.testSuiteCustomGeneratorsKotlin)
    ksp(projects.sourcegenGeneratorKotlin)
    ksp(projects.testSuiteCustomGenerators)
    ksp(mnValidation.micronaut.validation.processor)
    // KSP 2.3.11 (micronaut-build 8.1) no longer processes the test source set with the `ksp` configuration
    kspTest(mn.micronaut.inject.kotlin)

    implementation(mnValidation.micronaut.validation)
    implementation(mn.kotlin.stdlib)
    implementation(mn.micronaut.inject.kotlin)
    implementation(projects.sourcegenAnnotations)
    implementation(projects.testSuiteCustomAnnotations)

    testImplementation(mnTest.micronaut.test.junit5)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

