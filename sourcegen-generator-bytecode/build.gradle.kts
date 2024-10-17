plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

dependencies {
    implementation(projects.sourcegenGenerator)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    testImplementation(libs.junit.jupiter.engine)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
