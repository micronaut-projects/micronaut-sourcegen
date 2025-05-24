plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    api(projects.sourcegenGenerator)
    api(projects.sourcegenBytecodeWriter) {
        exclude("org.ow2.asm")
    }

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
