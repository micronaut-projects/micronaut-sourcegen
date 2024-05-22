plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(projects.sourcegenGeneratorJava)
    annotationProcessor(projects.testSuiteCustomGenerators)
    implementation(projects.sourcegenAnnotations)

    implementation(projects.testSuiteCustomAnnotations)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
    testAnnotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mnSecurity.micronaut.security.annotations)
    implementation(mnSecurity.micronaut.security)
    annotationProcessor(mnSerde.micronaut.serde.processor)
    implementation(mnSerde.micronaut.serde.jackson)
    annotationProcessor(mnData.micronaut.data.processor)
    implementation(mnData.micronaut.data.jdbc)
    implementation(mnSql.micronaut.jdbc.hikari)
    implementation(mnSql.h2)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    testRuntimeOnly(mnLogging.logback.classic)
}
