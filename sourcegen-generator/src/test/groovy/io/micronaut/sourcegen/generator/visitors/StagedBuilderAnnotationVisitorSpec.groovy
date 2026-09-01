package io.micronaut.sourcegen.generator.visitors

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec

class StagedBuilderAnnotationVisitorSpec extends AbstractTypeElementSpec {

    void "test staged builder assigns the required properties in stages"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.StagedBuilder;

        @StagedBuilder(annotatedWith = {})
        public record Walrus(
              String name,
              int age
        ) {
        }
        """)
        var stagedBuilderClass = classLoader.loadClass("test.WalrusStagedBuilder")
        var nameStage = classLoader.loadClass("test.WalrusStagedBuilder\$NameBuildStage")
        var ageStage = classLoader.loadClass("test.WalrusStagedBuilder\$AgeBuildStage")
        var buildFinal = classLoader.loadClass("test.WalrusStagedBuilder\$BuildFinal")

        when:
        var walrus = stagedBuilderClass.builder()
                .name("Ted the Walrus")
                .age(1)
                .build()

        then: "each stage assigns a single property and returns the next one"
        stagedBuilderClass.getMethod("builder").returnType == nameStage
        nameStage.getMethod("name", String).returnType == ageStage
        ageStage.getMethod("age", int).returnType == buildFinal
        buildFinal.getMethod("build").returnType == classLoader.loadClass("test.Walrus")

        and:
        walrus.name == "Ted the Walrus"
        walrus.age == 1
    }

    void "test the properties the builder can leave unassigned are on the final stage"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.core.annotation.Nullable;
        import io.micronaut.core.bind.annotation.Bindable;
        import io.micronaut.sourcegen.annotations.Singular;
        import io.micronaut.sourcegen.annotations.StagedBuilder;

        import java.util.List;

        @StagedBuilder(annotatedWith = {})
        public record Walrus(
              String name,
              @Nullable String nickname,
              @Bindable(defaultValue = "1") int age,
              @Singular List<String> friends
        ) {
        }
        """)
        var stagedBuilderClass = classLoader.loadClass("test.WalrusStagedBuilder")
        var nameStage = classLoader.loadClass("test.WalrusStagedBuilder\$NameBuildStage")
        var buildFinal = classLoader.loadClass("test.WalrusStagedBuilder\$BuildFinal")

        expect: "the only required property is the one that has no value when unassigned"
        stagedBuilderClass.getMethod("builder").returnType == nameStage
        nameStage.declaredMethods.collect { it.name } == ["name"]
        buildFinal.declaredMethods.collect { it.name }.toSorted() ==
                ["age", "build", "clearFriends", "friend", "friends", "nickname"]

        when: "the final stage is not assigned at all"
        var walrus = stagedBuilderClass.builder().name("Ted the Walrus").build()

        then:
        walrus.nickname() == null
        walrus.age() == 1
        walrus.friends() == []

        when: "the final stage is assigned in any order"
        var assigned = stagedBuilderClass.builder()
                .name("Ted the Walrus")
                .friend("Sam")
                .age(2)
                .nickname("Ted")
                .friend("Alex")
                .build()

        then:
        assigned.nickname() == "Ted"
        assigned.age() == 2
        assigned.friends() == ["Sam", "Alex"]
    }

    void "test staged builder of a record without properties"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.StagedBuilder;

        @StagedBuilder(annotatedWith = {})
        public record Walrus() {
        }
        """)
        var stagedBuilderClass = classLoader.loadClass("test.WalrusStagedBuilder")

        expect: "the build method is reachable right away"
        stagedBuilderClass.getMethod("builder").returnType ==
                classLoader.loadClass("test.WalrusStagedBuilder\$BuildFinal")
        stagedBuilderClass.builder().build() != null
    }

    void "test staged builder of a bean assigning the properties through setters"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.StagedBuilder;

        @StagedBuilder(annotatedWith = {})
        public class Walrus {

            private String name;
            private int age;

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public int getAge() {
                return age;
            }

            public void setAge(int age) {
                this.age = age;
            }
        }
        """)
        var stagedBuilderClass = classLoader.loadClass("test.WalrusStagedBuilder")

        when:
        var walrus = stagedBuilderClass.builder()
                .name("Ted the Walrus")
                .age(1)
                .build()

        then:
        walrus.name == "Ted the Walrus"
        walrus.age == 1
    }

    void "test staged builder with generics adds type arguments to every stage"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.StagedBuilder;

        @StagedBuilder(annotatedWith = {})
        public record Walrus<I>(
              I name,
              int age
        ) {
        }
        """)
        var stagedBuilderClass = classLoader.loadClass("test.WalrusStagedBuilder")
        var nameStage = classLoader.loadClass("test.WalrusStagedBuilder\$NameBuildStage")
        var builderClass = classLoader.loadClass("test.WalrusStagedBuilder\$Builder")

        expect: "a nested interface cannot inherit the type variables of its enclosing type"
        nameStage.typeParameters.collect { it.name } == ["I"]
        builderClass.typeParameters.collect { it.name } == ["I"]

        when:
        var walrus = stagedBuilderClass.builder()
                .name("Ted the Walrus")
                .age(1)
                .build()

        then:
        walrus.name == "Ted the Walrus"
        walrus.age == 1
    }

    void "test the builder is introspected by default"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.StagedBuilder;

        @StagedBuilder
        public record Walrus(
              String name,
              int age
        ) {
        }
        """)

        expect:
        classLoader.loadClass("test.WalrusStagedBuilder\$Builder")
                .getAnnotation(io.micronaut.core.annotation.Introspected) != null
    }
}
