plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    api(projects.sourcegenModel)
    api(mn.micronaut.core.processor)
    implementation(projects.sourcegenAnnotations)
    implementation(mn.micronaut.http)
    testImplementation(mnData.micronaut.data.jdbc)
    testImplementation(mn.micronaut.inject.java.test)
    testImplementation(projects.sourcegenGeneratorJava)
}
