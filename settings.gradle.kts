pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.micronaut.build.shared.settings") version "6.6.4"
}

rootProject.name = "sourcegen-parent"

include("sourcegen-annotations")
include("sourcegen-model")
include("sourcegen-generator")
include("sourcegen-generator-java")
include("sourcegen-generator-kotlin")
include("sourcegen-bom")

include("test-suite-java")
//include("test-suite-groovy")
include("test-suite-kotlin")
include("test-suite-custom-annotations")
include("test-suite-custom-generators")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

configure<io.micronaut.build.MicronautBuildSettingsExtension> {
    importMicronautCatalog()
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
