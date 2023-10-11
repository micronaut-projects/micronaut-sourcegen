plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
}

dependencies {
    implementation(projects.testSuiteCustomAnnotations)
    implementation(projects.sourcegenGenerator)
}
