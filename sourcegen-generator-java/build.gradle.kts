plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    implementation(projects.sourcegenGenerator)
    testImplementation(libs.google.truth)
    testImplementation(libs.google.compile.testing)
    testImplementation(libs.google.jimfs)
    testImplementation(libs.mockito)
}

tasks.withType(Test::class.java).configureEach {
    useJUnit()
    predictiveSelection {
        enabled = false
    }
}

tasks.withType(Checkstyle::class.java).configureEach {
    exclude("**/javapoet/**")
}

spotless {
    java {
        targetExclude("**/javapoet/**")
    }
}
