plugins {
    id("io.micronaut.build.internal.sourcegen-base")
    id("io.micronaut.build.internal.bom")
}

micronautBuild {
    binaryCompatibility {
        enabledAfter("1.7.0")
    }
}
