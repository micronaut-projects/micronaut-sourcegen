plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    compileOnly(mn.micronaut.inject.java)
    implementation(projects.sourcegenAnnotations)
    implementation(libs.javapoet)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.javapoet)
    testAnnotationProcessor(project)
}
