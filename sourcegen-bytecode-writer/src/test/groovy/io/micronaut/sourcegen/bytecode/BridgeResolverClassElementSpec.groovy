package io.micronaut.sourcegen.bytecode

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.ast.ClassElement
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef

import javax.lang.model.element.Modifier

/**
 * The hierarchy of a source supertype is only available through the annotation-processing AST.
 */
class BridgeResolverClassElementSpec extends AbstractTypeElementSpec {

    void "a generic source parent produces the declaration-site bridge"() {
        given:
        ClassElement parent = buildClassElement('''
package test;

abstract class SourceValueHolder<T> {
    public abstract T value(T value);
}
''')
        def stringType = TypeDef.of(String)
        def method = MethodDef.builder("value")
            .addModifiers(Modifier.PUBLIC)
            .overrides()
            .addParameter("value", stringType)
            .returns(stringType)
            .build((aThis, params) -> params.get(0).returning())
        def child = ClassDef.builder("test.StringSourceHolder")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), stringType))
            .addMethod(method)
            .build()

        when:
        def bridges = BridgeResolver.resolve(child, method)

        then:
        bridges.size() == 1
        TypeUtils.getType(bridges[0].parameterTypes()[0], null).descriptor == 'Ljava/lang/Object;'
        TypeUtils.getType(bridges[0].returnType(), null).descriptor == 'Ljava/lang/Object;'
    }

    void "a source parent hierarchy is walked level by level"() {
        given:
        ClassElement parent = buildClassElement('''
package test;

abstract class Child<T extends CharSequence> extends Top<T> {
}

abstract class Top<A> {
    public abstract A id(A value);
}
''')
        def stringType = TypeDef.of(String)
        def method = MethodDef.builder("id")
            .addModifiers(Modifier.PUBLIC)
            .overrides()
            .addParameter("value", stringType)
            .returns(stringType)
            .build((aThis, params) -> params.get(0).returning())
        def child = ClassDef.builder("test.StringChild")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), stringType))
            .addMethod(method)
            .build()

        when:
        def bridges = BridgeResolver.resolve(child, method)

        then:
        // Top.id(A) erases to Object at its declaration site
        bridges.size() == 1
        TypeUtils.getType(bridges[0].parameterTypes()[0], null).descriptor == 'Ljava/lang/Object;'
    }

    void "a declaring type bound survives an intermediate source supertype"() {
        given:
        ClassElement parent = buildClassElement('''
package test;

interface Middle<B> extends Top<B> {
}

interface Top<A> {
    void set(A value);
}
''')
        def method = MethodDef.builder("set")
            .addModifiers(Modifier.PUBLIC)
            .overrides()
            .addParameter("value", TypeDef.variable("T"))
            .returns(TypeDef.VOID)
            .build()
        def child = ClassDef.builder("test.NumberExample")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number)))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.variable("T")))
            .addMethod(method)
            .build()

        when:
        def bridges = BridgeResolver.resolve(child, method)

        then:
        // The declared method erases to set(Number), the bridge to Top's set(Object)
        bridges.size() == 1
        TypeUtils.getType(bridges[0].parameterTypes()[0], null).descriptor == 'Ljava/lang/Object;'
    }

    void "a non generic source parent produces no bridge"() {
        given:
        ClassElement parent = buildClassElement('''
package test;

abstract class Plain {
    public abstract String value(String value);
}
''')
        def stringType = TypeDef.of(String)
        def method = MethodDef.builder("value")
            .addModifiers(Modifier.PUBLIC)
            .overrides()
            .addParameter("value", stringType)
            .returns(stringType)
            .build((aThis, params) -> params.get(0).returning())
        def child = ClassDef.builder("test.PlainChild")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent))
            .addMethod(method)
            .build()

        expect:
        BridgeResolver.resolve(child, method).isEmpty()
    }
}
