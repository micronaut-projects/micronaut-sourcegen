plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    api(projects.sourcegenModel)
    api(mn.micronaut.core.processor)
    implementation(projects.sourcegenAnnotations)
}
