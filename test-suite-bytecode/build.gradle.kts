plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
}

// The shared backend TCK: fixtures and tests every backend must satisfy. See test-suite-tck/README.md.
sourceSets {
    named("main") {
        java.srcDir("../test-suite-tck/src/main/java")
    }
    named("test") {
        java.srcDir("../test-suite-tck/src/test/java")
    }
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(projects.sourcegenGeneratorBytecode)
    annotationProcessor(projects.testSuiteCustomGenerators)

    implementation(projects.sourcegenAnnotations)
    implementation(projects.testSuiteCustomAnnotations)

    testAnnotationProcessor(mn.micronaut.inject.java)

    testImplementation(mnTest.micronaut.test.junit5)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
//
//tasks {
//    compileJava {
//        options.isFork = true
//        options.forkOptions.jvmArgs = listOf("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
//    }
//}

tasks.withType<Test>().configureEach {
    systemProperty("sourcegen.backend", "bytecode")
}
