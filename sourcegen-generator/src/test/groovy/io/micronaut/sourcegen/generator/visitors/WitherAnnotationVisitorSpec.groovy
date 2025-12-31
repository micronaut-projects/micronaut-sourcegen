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
            protected record Bar(String a, String b, String c) implements Foo\$BarWither {
            }
        }
        """)

        when:
        // Load generated types
        def witherInterface = classLoader.loadClass('demo.test.Foo$BarWither')
        def builderClass = classLoader.loadClass('demo.test.Foo$BarBuilder')
        def recordClass = classLoader.loadClass('demo.test.Foo$Bar')

        then:
        witherInterface != null
        builderClass != null
        recordClass != null

        and: "the record implements the expected wither interface"
        (recordClass.interfaces as List<Class>)*.name.contains('demo.test.Foo$BarWither')

        and: "the 'with()' method on the wither returns the correct builder type name (no duplicated package)"
        witherInterface.getMethod("with").returnType.name == 'demo.test.Foo$BarBuilder'

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

        and: 'the consumer-based with method is present and uses Consumer<Foo$BarBuilder>'
        def withConsumer = witherInterface.getMethod("with", java.util.function.Consumer)
        withConsumer != null
        withConsumer.parameterTypes[0].typeName.contains("java.util.function.Consumer")
    }
}
