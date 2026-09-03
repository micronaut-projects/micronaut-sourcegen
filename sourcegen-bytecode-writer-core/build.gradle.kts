plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

micronautBuild {
    binaryCompatibility {
        enabled.set(false)
    }
}

dependencies {
    api(projects.sourcegenModel)

    compileOnly(mn.micronaut.core.processor) {
        exclude("io.micronaut.sourcegen")
    }

    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mn.micronaut.core.processor) {
        exclude("io.micronaut.sourcegen")
    }
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
