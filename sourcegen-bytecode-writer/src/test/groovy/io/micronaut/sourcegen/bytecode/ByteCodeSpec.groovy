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
            def instance = clazz.newInstance()
            instance.callGenericLambda2("Hello!")
            instance.callGenericLambdaAst("Hello!")
    }

}
