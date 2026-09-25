plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
}

// The Python compiler (micronaut-inject-python) ships with Micronaut core 5.2+, which this branch does not use yet:
// only this suite resolves that core version (see `micronaut-python` in gradle/libs.versions.toml).
dependencies {
    testImplementation(platform(libs.micronaut.core.python))
    testImplementation(libs.micronaut.inject.python.test)

    testImplementation(projects.sourcegenAnnotations)
    testImplementation(projects.sourcegenGenerator)
    testImplementation(projects.sourcegenGeneratorJava)
}

// The Python compiler is itself built on Micronaut SourceGen: substitute the released artifacts it depends
// on with the modules of this build so that the annotation visitors of this version generate the sources.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.micronaut.sourcegen:micronaut-sourcegen-annotations")).using(project(":sourcegen-annotations"))
        substitute(module("io.micronaut.sourcegen:micronaut-sourcegen-model")).using(project(":sourcegen-model"))
        substitute(module("io.micronaut.sourcegen:micronaut-sourcegen-generator")).using(project(":sourcegen-generator"))
        substitute(module("io.micronaut.sourcegen:micronaut-sourcegen-generator-java")).using(project(":sourcegen-generator-java"))
    }
}
