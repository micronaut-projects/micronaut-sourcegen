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

    void "test builder setter keeps the TYPE_USE nullable annotation of a record component"() {
        given:
        var classLoader = buildClassLoader("test.AppResources", """
        package test;
        import io.micronaut.sourcegen.annotations.Builder;
        import io.micronaut.sourcegen.annotations.Wither;
        import org.jspecify.annotations.Nullable;

        @Builder(annotatedWith = {})
        @Wither
        public record AppResources(@Nullable Integer memoryMb, String name, String @Nullable [] aliases, int age) implements AppResourcesWither {
        }
        """)
        var builderClass = classLoader.loadClass("test.AppResourcesBuilder")
        var witherClass = classLoader.loadClass("test.AppResourcesWither")
        var nullable = classLoader.loadClass("org.jspecify.annotations.Nullable")

        expect: "the setter of the nullable component declares its parameter nullable"
        builderClass.getMethod("memoryMb", Integer).getAnnotatedParameterTypes()[0].isAnnotationPresent(nullable)
        builderClass.getMethod("aliases", String[]).getAnnotatedParameterTypes()[0].isAnnotationPresent(nullable)

        and: "so does the wither"
        witherClass.getMethod("withMemoryMb", Integer).getAnnotatedParameterTypes()[0].isAnnotationPresent(nullable)
        !witherClass.getMethod("withName", String).getAnnotatedParameterTypes()[0].isAnnotationPresent(nullable)

        and: "the setter of the non-null component is left alone"
        !builderClass.getMethod("name", String).getAnnotatedParameterTypes()[0].isAnnotationPresent(nullable)
        builderClass.getMethod("name", String).getParameterAnnotations()[0].length == 0
        builderClass.getMethod("age", int).getAnnotatedParameterTypes()[0].getAnnotations().length == 0

        and: "the builder still works with a null value"
        builderClass.builder().memoryMb(null).name("x").build().memoryMb() == null
    }

    void "test builder setter of a property declared with a declaration nullable annotation is nullable"() {
        given:
        var classLoader = buildClassLoader("test.AppResources", """
        package test;
        import io.micronaut.sourcegen.annotations.Builder;
        import jakarta.annotation.Nullable;

        @Builder(annotatedWith = {})
        public record AppResources(@Nullable Integer memoryMb, String name) {
        }
        """)
        var builderClass = classLoader.loadClass("test.AppResourcesBuilder")
        var nullable = classLoader.loadClass("org.jspecify.annotations.Nullable")

        expect: "Micronaut maps every Nullable annotation onto one stereotype, so the setter is annotated with JSpecify's"
        builderClass.getMethod("memoryMb", Integer).getAnnotatedParameterTypes()[0].isAnnotationPresent(nullable)
        !builderClass.getMethod("name", String).getAnnotatedParameterTypes()[0].isAnnotationPresent(nullable)
    }

    void "test builder setter keeps the nullable annotation of a nested type"() {
        given:
        var classLoader = buildClassLoader("test.Holder", """
        package test;
        import io.micronaut.sourcegen.annotations.Builder;
        import org.jspecify.annotations.Nullable;
        import java.util.Map;

        @Builder(annotatedWith = {})
        public record Holder(Map.@Nullable Entry<String, String> entry, Holder.@Nullable Inner inner) {
            public record Inner(String v) {}
        }
        """)
        var builderClass = classLoader.loadClass("test.HolderBuilder")
        var nullable = classLoader.loadClass("org.jspecify.annotations.Nullable")

        expect:
        builderClass.getMethod("entry", Map.Entry).getAnnotatedParameterTypes()[0].isAnnotationPresent(nullable)
        builderClass.getMethod("inner", classLoader.loadClass('test.Holder$Inner')).getAnnotatedParameterTypes()[0].isAnnotationPresent(nullable)
    }
}
