package io.micronaut.sourcegen.generator.visitors

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec

class WitherAnnotationVisitorSpec extends AbstractTypeElementSpec {

    void "inner record with @Wither and @Builder resolves builder type in correct package and compiles"() {
        given:
        // Reproduction: top-level class Foo with protected inner record Bar using @Wither and @Builder
        // The wither must reference the correct builder type for the inner record without duplicating the package.
        var classLoader = buildClassLoader("demo.test.Foo", """
        package demo.test;
        import io.micronaut.sourcegen.annotations.Wither;
        import io.micronaut.sourcegen.annotations.Builder;
        public class Foo {
            @Wither
            @Builder
            protected record Bar(String a, String b, String c) implements FooBarWither {
            }
        }
        """)

        when:
        // Load generated types
        def witherInterface = classLoader.loadClass('demo.test.FooBarWither')
        def builderClass = classLoader.loadClass('demo.test.FooBarBuilder')
        def recordClass = classLoader.loadClass('demo.test.Foo$Bar')

        then:
        witherInterface != null
        builderClass != null
        recordClass != null

        and: "the record implements the expected wither interface"
        (recordClass.interfaces as List<Class>)*.name.contains('demo.test.FooBarWither')

        and: "the 'with()' method on the wither returns the correct builder type name (no duplicated package)"
        witherInterface.getMethod("with").returnType.name == 'demo.test.FooBarBuilder'

        when: "instantiate via builder and build an instance"
        def builder = builderClass.newInstance(new Object[]{})
        def instance = builder.a("A").b("B").c("C").build()

        then:
        instance != null
        instance.class.name == 'demo.test.Foo$Bar'

        when: "use wither default method to mutate one property"
        def withA = witherInterface.getMethod("withA", String.class)
        def mutated = withA.invoke(instance, "X")

        then:
        mutated != null
        mutated.class.name == 'demo.test.Foo$Bar'
        // Accessors of record are named after components: a(), b(), c()
        mutated.getClass().getMethod("a").invoke(mutated) == "X"
        mutated.getClass().getMethod("b").invoke(mutated) == "B"
        mutated.getClass().getMethod("c").invoke(mutated) == "C"

        and: 'the consumer-based with method is present and uses Consumer<FooBarBuilder>'
        def withConsumer = witherInterface.getMethod("with", java.util.function.Consumer)
        withConsumer != null
        withConsumer.parameterTypes[0].typeName.contains("java.util.function.Consumer")
    }

    void "component level @Wither only generates with methods for the annotated components"() {
        given:
        var classLoader = buildClassLoader("demo.test.Cat", """
        package demo.test;
        import io.micronaut.sourcegen.annotations.Wither;
        public record Cat(
            @Wither String name,
            int age,
            @Wither String color
        ) implements CatWither {
        }
        """)

        when:
        def witherInterface = classLoader.loadClass('demo.test.CatWither')
        def recordClass = classLoader.loadClass('demo.test.Cat')

        then: "only the annotated components have a with method"
        witherInterface.methods*.name.toSorted() == ['age', 'color', 'name', 'withColor', 'withName']

        and: "accessors are declared for every component so the copy can be created"
        witherInterface.getMethod("age").returnType == int.class

        when:
        def instance = recordClass.getConstructor(String, int, String).newInstance("Tom", 3, "grey")
        def mutated = witherInterface.getMethod("withColor", String).invoke(instance, "black")

        then:
        mutated.name() == "Tom"
        mutated.age() == 3
        mutated.color() == "black"
    }

    void "component level @Wither restricts a type level @Wither"() {
        given:
        var classLoader = buildClassLoader("demo.test.Dog", """
        package demo.test;
        import io.micronaut.sourcegen.annotations.Wither;
        @Wither
        public record Dog(
            @Wither String name,
            int age
        ) implements DogWither {
        }
        """)

        when:
        def witherInterface = classLoader.loadClass('demo.test.DogWither')

        then:
        witherInterface.methods*.name.toSorted() == ['age', 'name', 'withName']
    }

    void "type level @Wither still generates with methods for all the components"() {
        given:
        var classLoader = buildClassLoader("demo.test.Fish", """
        package demo.test;
        import io.micronaut.sourcegen.annotations.Wither;
        @Wither
        public record Fish(String name, int age) implements FishWither {
        }
        """)

        when:
        def witherInterface = classLoader.loadClass('demo.test.FishWither')

        then:
        witherInterface.methods*.name.toSorted() == ['age', 'name', 'withAge', 'withName']
    }

    void "records without any @Wither annotation do not generate a wither"() {
        given:
        var classLoader = buildClassLoader("demo.test.Bird", """
        package demo.test;
        import io.micronaut.sourcegen.annotations.Builder;
        @Builder
        public record Bird(String name, int age) {
        }
        """)

        when:
        classLoader.loadClass('demo.test.BirdWither')

        then:
        thrown(ClassNotFoundException)
    }
}
