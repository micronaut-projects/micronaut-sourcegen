plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    implementation(projects.sourcegenGenerator)

    testImplementation(projects.testSuiteCustomGenerators)
    testImplementation(libs.google.truth)
    testImplementation(libs.google.compile.testing)
    testImplementation(libs.google.jimfs)
    testImplementation(mnTest.mockito.core)
}
micronautBuild {
    testFramework = io.micronaut.build.TestFramework.JUNIT5
}
tasks.withType<Test> {
    useJUnitPlatform()
}
tasks.test {
    develocity.predictiveTestSelection.enabled = false
}

tasks.withType<Checkstyle> {
    exclude("**/javapoet/**")
}

spotless {
    java {
        targetExclude("**/javapoet/**")
    }
}
