plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
}

dependencies {
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(projects.sourcegenGeneratorJava)
    implementation(projects.sourcegenAnnotations)
    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}
