plugins {
    id("java-gradle-plugin")
    id("io.micronaut.build.internal.sourcegen-testsuite")
}

repositories {
    mavenCentral()
}

dependencies {
    api(projects.testSuitePluginCommon)
    annotationProcessor(projects.testSuitePluginCommon)
    annotationProcessor(mn.micronaut.inject)
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(projects.sourcegenGeneratorJava)
    annotationProcessor(projects.sourcegenBuildPluginGenerator)
    implementation(projects.sourcegenBuildPluginAnnotations)

    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(mnTest.junit.jupiter.engine)
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
    }
}
