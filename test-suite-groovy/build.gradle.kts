plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    id("groovy")
}

dependencies {
    compileOnly(mn.micronaut.inject.groovy)
    compileOnly(projects.sourcegenGenerator)
    compileOnly(projects.sourcegenGeneratorJava)

//    implementation(mn.micronaut.inject.groovy)
//    implementation(projects.sourcegenGeneratorJava)
//    implementation(projects.sourcegenGenerator)

//    annotationProcessor(mn.micronaut.inject.groovy)
//    annotationProcessor(projects.sourcegenGeneratorJava)
//    annotationProcessor(projects.sourcegenGenerator)

    implementation(projects.sourcegenAnnotations)

    testImplementation(mnTest.micronaut.test.junit5)
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.3")
}
