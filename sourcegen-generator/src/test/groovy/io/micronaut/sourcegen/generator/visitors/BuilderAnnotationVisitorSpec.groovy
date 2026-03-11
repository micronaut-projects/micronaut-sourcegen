package io.micronaut.sourcegen.generator.visitors

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec

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

    void "test strict builder"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.Builder;
        import java.util.List;

        @Builder(annotatedWith = {}, strict = true)
        public record Walrus(
              String name,
              int age,
              byte[] chipInfo,
              List<String> list
        ) {
        }
        """)
        var walrusBuilderClass = classLoader.loadClass("test.WalrusBuilder")

        expect:
        var walrusBuilder = walrusBuilderClass.newInstance(new Object[]{})

        var walruses = walrusBuilder
                .name("Ted the Walrus")
                .age(0)
                .chipInfo(new byte[]{})
                .list(Collections.emptyList())
                .build()
        assert walruses.name == "Ted the Walrus"
        assert walruses.age == 0
        assert walruses.chipInfo != null
        assert walruses.list != null

        var walrusBuilder0 = walrusBuilderClass.newInstance(new Object[]{})
        try {
            var walrus = walrusBuilder0
                    .name("Ted the Walrus")
                    .age(1)
                    .age(10)
                    .chipInfo(new byte[]{})
                    .build()
        assert walrus.age == 1
        } catch (IllegalStateException ex) {
            assert ex.getMessage() == "age cannot be reinitialized."
        }
        var walrusBuilder1 = walrusBuilderClass.newInstance(new Object[]{})
        try {
            var walrus = walrusBuilder1
                    .name("Ted the Walrus")
                    .name("updated name")
                    .chipInfo(new byte[]{})
                    .list(Collections.emptyList())
                    .build()
           assert walrus.age == "Ted the Walrus"
        } catch (IllegalStateException ex) {
            assert ex.getMessage() == "name cannot be reinitialized."
        }
        var walrusBuilder2 = walrusBuilderClass.newInstance(new Object[]{})
        try {
            var walrus = walrusBuilder2
                    .name("Ted the Walrus")
                    .age(0)
                    .age(21)
                    .chipInfo(new byte[]{})
                    .build()
          assert walrus.age == 0
        } catch (IllegalStateException ex) {
            assert ex.getMessage() == "age cannot be reinitialized."
        }
    }
}
