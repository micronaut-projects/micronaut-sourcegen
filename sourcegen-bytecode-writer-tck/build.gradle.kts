plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

micronautBuild {
    binaryCompatibility {
        enabled.set(false)
    }
}

// The TCK is compiled against the baseline so that every backend can run it, including the JDK
// ClassFile backend, which builds on a newer toolchain.
dependencies {
    api(projects.sourcegenModel)
    api(mnTest.junit.jupiter.api)
    api(mn.micronaut.core.processor) {
        exclude("io.micronaut.sourcegen")
    }
}
