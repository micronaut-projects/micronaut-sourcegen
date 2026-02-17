pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.micronaut.build.shared.settings") version "8.0.0-M17"
}

rootProject.name = "sourcegen-parent"

include("sourcegen-annotations")
include("sourcegen-model")
include("sourcegen-generator")
include("sourcegen-generator-java")
include("sourcegen-generator-kotlin")
include("sourcegen-generator-bytecode")
include("sourcegen-bytecode-writer")
include("sourcegen-bom")

include("test-suite-java")
include("test-suite-bytecode")
//include("test-suite-groovy")
include("test-suite-kotlin")
include("test-suite-custom-annotations")
include("test-suite-custom-generators")
include("test-suite-custom-generators-kotlin")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

configure<io.micronaut.build.MicronautBuildSettingsExtension> {
    importMicronautCatalog()
    importMicronautCatalog("micronaut-data")
    importMicronautCatalog("micronaut-validation")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
