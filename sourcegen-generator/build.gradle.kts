plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    api(projects.sourcegenModel)
    api(mn.micronaut.core.processor) {
        exclude("org.yaml")
        exclude("io.micronaut.sourcegen")
    }

    implementation(projects.sourcegenAnnotations)

    testImplementation(mn.micronaut.inject.java.test)
    testImplementation(projects.sourcegenGeneratorJava)
}
