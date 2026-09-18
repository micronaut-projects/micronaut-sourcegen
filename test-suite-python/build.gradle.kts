import io.micronaut.build.python.PythonCompile

plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id("io.micronaut.build.internal.python")
}

// The Python compiler (micronaut-inject-python) ships with Micronaut core 5.2+, which this branch does not use yet: only this
// suite resolves that core version (see `micronaut-python` in gradle/libs.versions.toml).
micronautBuild {
    python {
        compilerVersion.set(libs.versions.micronaut.python)
    }
}

dependencies {
    testImplementation(platform(libs.micronaut.core.python))

    // Annotation processors MUST be testImplementation (not testAnnotationProcessor): the Python
    // compiler takes the (jar-resolved) compile classpath as its annotation processor path.
    testImplementation(projects.sourcegenGeneratorJava)
    testImplementation(libs.micronaut.inject.python.test)
    testImplementation(libs.micronaut.context.python)

    testImplementation(projects.sourcegenAnnotations)
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
    // A Truffle host-interop assertion trips on varargs overloads
    enableAssertions = false
}

// TODO(python): the documentation classes live in src/main/python (the `source="main"` snippets) and the
// tests in src/test/python. Compiling them separately yields two GraalPy VFS roots whose generated shim
// modules shadow each other at test time, and the Python compiler resolves the imports of a source file
// only within its own source root, so both roots are merged into one directory compiled with the tests.
val mergedPythonSources = tasks.register<Sync>("mergePythonSources") {
    from(layout.projectDirectory.dir("src/main/python"))
    from(layout.projectDirectory.dir("src/test/python"))
    into(layout.buildDirectory.dir("merged-python-sources"))
}
tasks.named("compilePython") {
    enabled = false
}
tasks.named<PythonCompile>("compileTestPython") {
    dependsOn(mergedPythonSources)
    source.setFrom(mergedPythonSources.map { it.destinationDir })
}

