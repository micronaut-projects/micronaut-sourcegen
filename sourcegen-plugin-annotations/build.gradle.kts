plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

micronautBuild {
    binaryCompatibility {
        enabledAfter("1.7.0")
    }
}
