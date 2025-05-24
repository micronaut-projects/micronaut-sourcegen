plugins {
    id("io.micronaut.build.internal.sourcegen-lib-module")
}

dependencies {
    compileOnly(mn.micronaut.core.processor)

    testImplementation(mn.micronaut.core.processor)
    testImplementation(mnTest.junit.jupiter.api)

    testRuntimeOnly(mnTest.junit.jupiter.engine)
}
