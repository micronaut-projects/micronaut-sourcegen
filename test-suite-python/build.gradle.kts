plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id("io.micronaut.build.internal.python")
}

dependencies {
    // Annotation processors MUST be testImplementation (not testAnnotationProcessor): the Python
    // compiler takes the (jar-resolved) compile classpath as its annotation processor path.
    compileOnly(projects.sourcegenGeneratorJava)
    testImplementation(projects.sourcegenGeneratorJava)
    testImplementation(mn.micronaut.inject.python.test)
    testImplementation(mn.micronaut.context.python)

    implementation(projects.sourcegenAnnotations)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mnTest.junit.jupiter.api)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
    testRuntimeOnly(mnTest.junit.platform.launcher)
    testRuntimeOnly(mnLogging.logback.classic)
}

// The Python compiler is itself built on Micronaut SourceGen: substitute the released artifacts it depends
// on with the modules of this build so that the annotation visitors of this version generate the samples.
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.micronaut.sourcegen:micronaut-sourcegen-annotations")).using(project(":sourcegen-annotations"))
        substitute(module("io.micronaut.sourcegen:micronaut-sourcegen-model")).using(project(":sourcegen-model"))
        substitute(module("io.micronaut.sourcegen:micronaut-sourcegen-generator")).using(project(":sourcegen-generator"))
        substitute(module("io.micronaut.sourcegen:micronaut-sourcegen-generator-java")).using(project(":sourcegen-generator-java"))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("micronaut.python.pool.enabled", "false")
}
