plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    api(libs.managed.javapoet) {
        because("Groovy annotation processing would fail without it")
    }
    implementation(projects.sourcegenGenerator)
}

