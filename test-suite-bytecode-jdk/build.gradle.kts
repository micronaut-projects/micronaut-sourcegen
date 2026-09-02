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
