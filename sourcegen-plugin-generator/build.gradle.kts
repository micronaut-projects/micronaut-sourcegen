plugins {
    id("io.micronaut.build.internal.sourcegen-module")
}

repositories {
    maven {
        setUrl("https://repo.gradle.org/gradle/libs-releases")
    }
}

dependencies {
    api(projects.sourcegenModel)
    implementation(projects.sourcegenGenerator)
    api(mn.micronaut.core.processor)
    implementation(projects.sourcegenPluginAnnotations)

    testImplementation(projects.sourcegenAnnotations)
    testImplementation(mn.micronaut.inject.java.test)
    testImplementation(projects.sourcegenGeneratorJava)

    testImplementation("dev.gradleplugins:gradle-api:8.11.1") {
        exclude( "org.codehaus.groovy", "groovy")
    }
    testImplementation("org.apache.maven.plugin-tools:maven-plugin-annotations:3.9.0")
    testImplementation("org.apache.maven:maven-plugin-api:3.9.4")
}

micronautBuild {
    binaryCompatibility {
        enabledAfter("1.7.0")
    }
}
