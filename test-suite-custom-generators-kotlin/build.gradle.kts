plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
    alias(mn.plugins.kotlin.jvm)
    alias(mn.plugins.kotlin.allopen)
    alias(mn.plugins.ksp)
}

dependencies {
    implementation(projects.testSuiteCustomAnnotations)
    implementation(projects.sourcegenGenerator)
    implementation(projects.sourcegenAnnotations)
    implementation(mn.kotlin.stdlib)
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
