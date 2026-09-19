plugins {
    id("io.micronaut.build.internal.sourcegen-base")
    id("io.micronaut.build.internal.parent")
}

if (System.getenv("SONAR_TOKEN") != null) {
    tasks.named("testCodeCoverageReport") { enabled = false }
}

afterEvaluate {
    configurations.javadocAggregatorBase.configure {
        dependencies.removeIf {
            it.name.startsWith("test-suite")
        }
    }
    // The test suites generate the same example types with different generators - `test-suite-java`
    // generates `io.micronaut.sourcegen.example.CrudRepository1` from source and `test-suite-bytecode`
    // generates it as bytecode - so aggregating their classes fails the report with
    // `Can't add different class with same name`. Their coverage is not meaningful anyway.
    configurations.named("jacocoAggregation") {
        dependencies.removeIf {
            it.name.startsWith("test-suite")
        }
    }
}


// The bytecode writer TCK is test code that ships as a jar so that every backend can run it. Sonar
// applies its Gradle plugin only to the root project, so a module cannot declare its own sources as
// tests; without this the TCK is analysed as production code and reports rules that do not hold for
// test code, such as assertions not belonging in production code.
plugins.withId("org.sonarqube") {
    extensions.findByName("sonar")?.withGroovyBuilder {
        "properties" {
            "property"("sonar.exclusions", "**/io/micronaut/sourcegen/bytecode/tck/**")
            // Micronaut builds pin dependency versions through the version catalog and do not
            // publish Gradle lock files or dependency verification metadata
            "property"("sonar.issue.ignore.multicriteria", "lockfile,verification")
            "property"("sonar.issue.ignore.multicriteria.lockfile.ruleKey", "text:S8569")
            "property"("sonar.issue.ignore.multicriteria.lockfile.resourceKey", "**/*")
            "property"("sonar.issue.ignore.multicriteria.verification.ruleKey", "kotlin:S6474")
            "property"("sonar.issue.ignore.multicriteria.verification.resourceKey", "**/*")
        }
    }
}
