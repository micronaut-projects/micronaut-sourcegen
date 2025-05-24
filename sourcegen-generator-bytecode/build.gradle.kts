plugins {
    id("io.micronaut.build.internal.sourcegen-lib-module")
}

dependencies {
    api(projects.sourcegenGenerator)
    api(projects.sourcegenBytecodeWriter)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
