plugins {
    id("io.micronaut.build.internal.sourcegen-testsuite")
}

// Writes the same models through the four backends - the ASM and the JDK ClassFile bytecode
// writers, and the Java and Kotlin source generators compiled with javac and kotlinc - and compares
// what the written classes do. The JDK ClassFile API needs Java 25.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    testImplementation(projects.sourcegenModel)
    testImplementation(projects.sourcegenGenerator)
    testImplementation(projects.sourcegenGeneratorJava)
    testImplementation(projects.sourcegenGeneratorKotlin)
    testImplementation(projects.sourcegenBytecodeWriter)
    testImplementation(projects.sourcegenBytecodeWriterJdk)
    testImplementation(projects.sourcegenBytecodeWriterTck)
    testImplementation(mn.kotlin.compiler.embeddable)
    testImplementation(mn.kotlin.stdlib)
    testImplementation(mnTest.junit.jupiter.api)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
}

// The default run is a fixed seed and bounded. Widen it for exploration, for example
// ./gradlew :test-suite-differential:test -Pdiff.count=0 -Pdiff.random=2000 -Pdiff.seed=42
// (see DifferentialHarnessTest for the properties).
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    listOf("diff.families", "diff.seed", "diff.random", "diff.count").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}
