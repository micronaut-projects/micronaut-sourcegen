package io.micronaut.sourcegen.bytecode

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec

class ByteCodeSpec extends AbstractTypeElementSpec {

    def "test generic method with lambdas"() {
        when:
            ClassLoader classLoader = buildClassLoader("example.Test", """
package example;

import io.micronaut.sourcegen.custom.example.GenerateLambda;

@GenerateLambda
class Trigger {
}


""")
        then:
            classLoader
            def clazz = classLoader.loadClass("example.MyClassWithLambda")
            def instance = clazz.getDeclaredConstructor().newInstance()
            instance.callGenericLambda2("Hello!")
            instance.callGenericLambdaAst("Hello!")
    }

    def "test generic predicates"() {
        when:
            ClassLoader classLoader = buildClassLoader("example.Test", """
package example;

import io.micronaut.sourcegen.custom.example.GenerateIfsPredicate;

@GenerateIfsPredicate
class Trigger {
}


""")
        then:
            classLoader

            def ifPredicateGeneric2 = classLoader.loadClass("example.IfPredicateGeneric2").newInstance()
            ifPredicateGeneric2.test(1)
            !ifPredicateGeneric2.test(2)
        and:
            def ifPredicateGeneric3 = classLoader.loadClass("example.IfPredicateGeneric3").newInstance()
            ifPredicateGeneric3.test(1)
            !ifPredicateGeneric3.test(2)
        and:
            def ifPredicateGeneric4 = classLoader.loadClass("example.IfPredicateGeneric4").newInstance()
            ifPredicateGeneric4.test("foobar")
            !ifPredicateGeneric4.test("abc")
    }

}
