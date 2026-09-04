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
    api(projects.sourcegenModel)
    api(projects.sourcegenBytecodeWriterCore)
    implementation(projects.sourcegenGenerator) {
        exclude("org.ow2.asm", "asm")
    }
    implementation(projects.sourcegenGeneratorJava) {
        exclude("org.ow2.asm", "asm")
    }

    testImplementation(projects.sourcegenBytecodeWriterTck)
    testImplementation(mnTest.junit.jupiter.api)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
