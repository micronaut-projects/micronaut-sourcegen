plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

micronautBuild {
    binaryCompatibility {
        enabled.set(false)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    api(projects.sourcegenGenerator) {
        exclude("org.ow2.asm", "asm")
    }
    implementation(projects.sourcegenBytecodeWriterJdk) {
        exclude("org.ow2.asm", "asm")
    }
    implementation(projects.sourcegenGeneratorJava) {
        exclude("org.ow2.asm", "asm")
    }

    testImplementation(projects.sourcegenAnnotations)
    testImplementation(mn.micronaut.inject.java.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
