plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    compileOnly(mn.micronaut.core.processor)

    testImplementation(mn.micronaut.inject.java)
    testImplementation(mn.micronaut.core.processor)
    testImplementation(mnTest.junit.jupiter.api)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
