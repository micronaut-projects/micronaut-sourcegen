plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id ("io.micronaut.build.internal.kotlin-ksp")
}

dependencies {
    implementation(projects.testSuiteCustomAnnotations)
    implementation(projects.sourcegenGenerator)
    implementation(projects.sourcegenAnnotations)
    implementation(mn.kotlin.stdlib)
}
