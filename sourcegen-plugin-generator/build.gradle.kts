plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    api(projects.sourcegenModel)
    implementation(projects.sourcegenGenerator)
    api(mn.micronaut.core.processor)
    implementation(projects.sourcegenPluginAnnotations)

    testImplementation(projects.sourcegenAnnotations)
    testImplementation(mn.micronaut.inject.java.test)
    testImplementation(projects.sourcegenGeneratorJava)
}

micronautBuild {
    binaryCompatibility {
        enabledAfter("1.7.0")
    }
}
