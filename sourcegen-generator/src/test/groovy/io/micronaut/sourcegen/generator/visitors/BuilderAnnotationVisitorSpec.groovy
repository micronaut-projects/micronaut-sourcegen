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

    void "test builder for properties whose accessors the JavaBeans rules do not decapitalize"() {
        given: "a bean whose accessor gives a property name that differs from the field and the constructor parameter behind it"
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.Builder;

        @Builder(annotatedWith = {})
        public class Walrus {

            private final String aBC;
            private final int x;
            private final String URL;

            public Walrus(String aBC, int x, String URL) {
                this.aBC = aBC;
                this.x = x;
                this.URL = URL;
            }

            public String getABC() {
                return aBC;
            }

            public int getX() {
                return x;
            }

            public String getURL() {
                return URL;
            }
        }
        """)
        var walrusBuilderClass = classLoader.loadClass("test.WalrusBuilder")

        when: "the builder assigns every property"
        var walrus = walrusBuilderClass.builder()
                .ABC("Ted the Walrus")
                .x(1)
                .URL("https://example.com")
                .build()

        then: "the constructor is given the value the setter assigned, so the two agree on the field behind the property"
        walrus.getABC() == "Ted the Walrus"
        walrus.getX() == 1
        walrus.getURL() == "https://example.com"
    }
}
