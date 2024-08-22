plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id("io.micronaut.application")
}

micronaut {
    processing {
        // test incremental compile
        incremental(true)
    }
    version.set(libs.versions.micronaut.platform)
}
dependencies {
    annotationProcessor(projects.sourcegenGeneratorJava)
    annotationProcessor(projects.testSuiteCustomGenerators)
    annotationProcessor(mnData.micronaut.data.processor)
    implementation(projects.sourcegenAnnotations)
    implementation(projects.testSuiteCustomAnnotations)
    implementation(mnData.micronaut.data.model)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
}
//
//tasks {
//    compileJava {
//        options.isFork = true
//        options.forkOptions.jvmArgs = listOf("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
//    }
//}
