pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.micronaut.build.shared.settings") version "6.5.3"
}

rootProject.name = "sourcegen-parent"

include("sourcegen-annotations")
include("sourcegen-core")
include("sourcegen-bom")
include("test-suite-java")
include("test-suite-kotlin")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

configure<io.micronaut.build.MicronautBuildSettingsExtension> {
    importMicronautCatalog()
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}
