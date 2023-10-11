plugins {
    id("io.micronaut.build.internal.sourcegen-base")
    id("io.micronaut.build.internal.bom")
}

micronautBuild {
    binaryCompatibility {
        enabled.set(false)
    }
}
