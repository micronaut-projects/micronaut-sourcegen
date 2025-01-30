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

    void "test builder for non-standard property"() {
        given:
        var classLoader = buildClassLoader("test.Walrus", """
        package test;
        import io.micronaut.sourcegen.annotations.Builder;

        @Builder(annotatedWith = {})
        public class Walrus {
           private String aBC;

           public Walrus(
                   String aBC
           ) {
               this.aBC = aBC;
           }

           public void getABC(String aBC) {
               this.aBC = aBC;
           }

        }
        """)
        var walrusBuilderClass = classLoader.loadClass("test.WalrusBuilder")

        expect:
        var walrusBuilder = walrusBuilderClass.newInstance(new Object[]{ "hello" })
        var walrus = walrusBuilder.build()
        walrus != null
        walrus.aBC == "hello"
    }

}
