plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    implementation(projects.sourcegenAnnotations)

    compileOnly(mn.micronaut.core.processor) {
        exclude("io.micronaut.sourcegen")
    }

    testImplementation(mn.micronaut.inject.java) {
        exclude("io.micronaut.sourcegen")
    }
    testImplementation(mn.micronaut.core.processor) {
        exclude("io.micronaut.sourcegen")
    }
    testImplementation(mnTest.junit.jupiter.api)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
