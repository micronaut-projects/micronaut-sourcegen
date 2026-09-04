plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id("io.micronaut.minimal.application")
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

micronaut {
    version.set(libs.versions.micronaut.platform)
    processing {
        // test incremental compile
        incremental(true)
    }
}

dependencies {
    annotationProcessor(projects.sourcegenGeneratorJava)
    annotationProcessor(projects.testSuiteCustomGenerators)
    annotationProcessor(mnData.micronaut.data.processor)
    annotationProcessor(mnValidation.micronaut.validation.processor)

    implementation(mnValidation.micronaut.validation)
    implementation(projects.sourcegenAnnotations)
    implementation(projects.testSuiteCustomAnnotations)
    implementation(mnData.micronaut.data.model)

    testAnnotationProcessor(mn.micronaut.inject.java.test)

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
    systemProperty("sourcegen.backend", "java")
}
