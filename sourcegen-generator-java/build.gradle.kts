plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    implementation(projects.sourcegenGenerator)

    testImplementation(libs.google.truth)
    testImplementation(libs.google.compile.testing)
    testImplementation(libs.google.jimfs)
    testImplementation(mnTest.mockito.core)
}

tasks.test {
    useJUnit()
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
