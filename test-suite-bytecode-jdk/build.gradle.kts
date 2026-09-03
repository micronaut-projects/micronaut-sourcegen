plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
}

sourceSets {
    named("main") {
        java.srcDirs("../test-suite-bytecode/src/main/java")
    }
    named("test") {
        java.srcDirs("../test-suite-bytecode/src/test/java")
    }
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(projects.sourcegenGeneratorBytecodeJdk)
    annotationProcessor(projects.testSuiteCustomGenerators)

    implementation(projects.sourcegenAnnotations)
    implementation(projects.testSuiteCustomAnnotations)

    testAnnotationProcessor(mn.micronaut.inject.java)
    testImplementation(mnTest.micronaut.test.junit5)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

// The shared source sets live outside this project directory, so javac needs them on the
// generator's source path to resolve types that are still being compiled.
val sharedSourceRoots = listOf(
    layout.projectDirectory.dir("../test-suite-bytecode/src/main/java"),
    layout.projectDirectory.dir("../test-suite-bytecode/src/test/java")
).joinToString(File.pathSeparator) { it.asFile.canonicalPath }

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Amicronaut.sourcegen.bytecode.jdk.sourcepath=$sharedSourceRoots")
}
