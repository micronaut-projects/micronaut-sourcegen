package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec;

class BuilderAnnotationVisitorSpec extends AbstractTypeElementSpec {

    void "test builder"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.Builder;

        @Builder(annotatedWith = {})
        public record Walrus(
              String name,
              int age,
              byte[] chipInfo
        ) {
        }
        """)
        var walrusBuilderClass = classLoader.loadClass("test.WalrusBuilder")

        expect:
        var walrusBuilder = walrusBuilderClass.newInstance(new Object[]{})
        var walrus = walrusBuilder
                .name("Ted the Walrus")
                .age(1).build()
        walrus.name == "Ted the Walrus"
        walrus.age == 1
    }

    void "test empty builder"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.Builder;

        @Builder(annotatedWith = {})
        public record Walrus() {
        }
        """)
        var walrusBuilderClass = classLoader.loadClass("test.WalrusBuilder")

        expect:
        var walrusBuilder = walrusBuilderClass.newInstance(new Object[]{})
        var walrus = walrusBuilder.build()
        walrus != null
    }

    void "test builder with generics adds type arguments to builder method"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.Builder;
        import io.micronaut.sourcegen.annotations.Wither;

        @Builder
        @Wither
        public record Walrus<I>(
              I name,
              int age,
              byte[] chipInfo
        ) implements WalrusWither<I> {
        }
        """)
        var walrusBuilderClass = classLoader.loadClass("test.WalrusBuilder")

        expect:
        var walrusBuilder = walrusBuilderClass.builder()
        walrusBuilderClass.getTypeParameters().size() == 1
        walrusBuilderClass.getTypeParameters()[0].name == "I"
        var walrus = walrusBuilder
                .name("Ted the Walrus")
                .age(1).build()
        walrus.name == "Ted the Walrus"
        walrus.age == 1
    }
}
