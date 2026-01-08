plugins {
    id("io.micronaut.build.internal.sourcegen-module")
    alias(mn.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(projects.sourcegenGenerator)
    implementation(libs.managed.kotlinpoet)
    implementation(libs.managed.kotlinpoet.javapoet)

    testImplementation(mnTest.micronaut.test.junit5)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

tasks.test {
    develocity.predictiveTestSelection.enabled = false
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
