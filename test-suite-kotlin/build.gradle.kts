plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id("org.jetbrains.kotlin.jvm")
    id("com.google.devtools.ksp") version mn.versions.ksp
    id("org.jetbrains.kotlin.plugin.allopen")
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
