package io.micronaut.sourcegen.bytecode

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.sourcegen.bytecode.core.DeclaredReturns
import io.micronaut.sourcegen.bytecode.core.TypeUtils
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef

/**
 * Inference of method type arguments for methods whose declarations exist only as compiler elements:
 * wildcard capture, bound constraints, generic variable arity calls and requested return types.
 */
class InferenceClassElementSpec extends AbstractTypeElementSpec {

    void "source wildcard constraints exclude an inapplicable overload"() {
        given:
        def target = buildClassElement('''
package test;
class SourceWildcard {
    public static String choose(java.util.List<? extends Number> value) { return "number"; }
    public static String choose(Object value) { return "object"; }
}
''')
        def argument = ExpressionDef.nullValue().cast(TypeDef.parameterized(List, TypeDef.STRING))

        when:
        def call = ClassTypeDef.of(target).invokeStatic("choose", TypeDef.STRING, argument)

        then:
        TypeUtils.getMethodDescriptor(null, call.method()) == '(Ljava/lang/Object;)Ljava/lang/String;'
    }

    void "source generic varargs infer the runtime component"() {
        given:
        def target = buildClassElement('''
package test;
class SourceVarargs {
    public static <T> String component(T... values) { return values.getClass().getComponentType().getName(); }
}
''')

        when:
        def call = ClassTypeDef.of(target).invokeStatic("component", TypeDef.STRING, ExpressionDef.constant("abc"))

        then:
        TypeUtils.getDescriptor(call.values().last().type(), null) == '[Ljava/lang/String;'
    }

    void "source generic overloads respect wildcard capture and common inference constraints"() {
        given:
        def target = buildClassElement('''
package test;
class SourceOverloads {
    public static <T> String capture(java.util.List<T> values, T value) { return "generic"; }
    public static String capture(Object values, Object value) { return "fallback"; }
    public static <T> String same(java.util.List<T> first, java.util.List<T> second) { return "generic"; }
    public static String same(Object first, Object second) { return "fallback"; }
    public static <T> String transfer(java.util.List<? extends T> first, java.util.List<? super T> second) { return "generic"; }
    public static String transfer(Object first, Object second) { return "fallback"; }
    public static <T extends Comparable<T>> String recursive(T first, T second) { return "generic"; }
    public static String recursive(Object first, Object second) { return "fallback"; }
    public static <T> String upperOnly(java.util.List<? super T> first, java.util.List<? super T> second) { return "generic"; }
    public static String upperOnly(Object first, Object second) { return "fallback"; }
}
''')
        def arguments = types.collect { ExpressionDef.nullValue().cast(it) }

        when:
        def call = ClassTypeDef.of(target).invokeStatic(name, TypeDef.STRING, arguments)

        then:
        TypeUtils.getMethodDescriptor(null, call.method()) == '(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;'

        where:
        name        | types
        'capture'   | [list(TypeDef.wildcardSubtypeOf(TypeDef.of(Number))), TypeDef.of(Integer)]
        'same'      | [list(TypeDef.wildcardSubtypeOf(TypeDef.of(Number))), list(TypeDef.wildcardSubtypeOf(TypeDef.of(Number)))]
        'transfer'  | [list(TypeDef.wildcardSubtypeOf(TypeDef.of(Number))), list(TypeDef.STRING)]
        'recursive' | [TypeDef.STRING, TypeDef.of(Integer)]
        'upperOnly' | [list(TypeDef.STRING), list(TypeDef.of(Integer))]
    }

    void "source generic target types keep erased method descriptors"() {
        given:
        def target = buildClassElement('''
package test;
class SourceTargetTypes<T> {
    public T get() { return null; }
    public <V extends T> V echo(V value) { return value; }
    public static <U extends Number> U identity(U value) { return value; }
    public static <U extends Number> U target(String value) { return null; }
    public static Integer target(Object value) { return -1; }
    public static <U> U create() { return null; }
}
''')
        def parameterized = TypeDef.parameterized(ClassTypeDef.of(target), TypeDef.STRING)

        when:
        def receiverCall = ExpressionDef.nullValue().cast(parameterized).invoke('get', TypeDef.STRING)
        def combinedCall = ExpressionDef.nullValue().cast(parameterized).invoke('echo', TypeDef.STRING, ExpressionDef.constant('value'))
        def identityCall = ClassTypeDef.of(target).invokeStatic('identity', TypeDef.of(Integer),
            ExpressionDef.constant(1).cast(TypeDef.of(Integer)))
        def overloadCall = ClassTypeDef.of(target).invokeStatic('target', TypeDef.of(Integer), ExpressionDef.constant('value'))
        def createCall = ClassTypeDef.of(target).invokeStatic('create', TypeDef.STRING)

        then:
        [receiverCall, combinedCall, identityCall, overloadCall, createCall].collect { writtenDescriptor(it) } == [
            '()Ljava/lang/Object;',
            '(Ljava/lang/Object;)Ljava/lang/Object;',
            '(Ljava/lang/Number;)Ljava/lang/Number;',
            '(Ljava/lang/String;)Ljava/lang/Number;',
            '()Ljava/lang/Object;'
        ]
    }

    void "source inherited generic method keeps the erasure its declaration has"() {
        given:
        def target = buildClassElement('''
package test;
class StringGetter extends Getter<String> {
    public Integer size() { return 1; }
}
class Getter<T> {
    public T get() { return null; }
}
''')

        when:
        def byName = ExpressionDef.nullValue().cast(ClassTypeDef.of(target)).invoke('get', TypeDef.STRING)
        def explicit = ExpressionDef.nullValue().cast(ClassTypeDef.of(target))
            .invoke(MethodDef.builder('get').returns(TypeDef.OBJECT).build())
        def declared = ExpressionDef.nullValue().cast(ClassTypeDef.of(target))
            .invoke(MethodDef.builder('size').returns(TypeDef.of(Integer)).build())

        then:
        [byName, explicit, declared].collect { writtenDescriptor(it) } == [
            '()Ljava/lang/Object;',
            '()Ljava/lang/Object;',
            '()Ljava/lang/Integer;'
        ]
    }

    void "source lower wildcard contributes an upper inference constraint"() {
        given:
        def target = buildClassElement('''
package test;
class Lower {
    public static <T> String choose(java.util.List<? super T> list, T value) { return "generic"; }
    public static String choose(Object list, Object value) { return "object"; }
}
''')
        def list = ExpressionDef.nullValue().cast(TypeDef.parameterized(List, TypeDef.STRING))

        when:
        def call = ClassTypeDef.of(target).invokeStatic("choose", TypeDef.STRING, list, ExpressionDef.constant(7))

        then:
        TypeUtils.getMethodDescriptor(null, call.method()) == '(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;'
    }

    void "source wildcard argument excludes an incompatible upper bound"() {
        given:
        def target = buildClassElement('''
package test;
class Upper {
    public static String choose(java.util.List<? extends Number> list) { return "number"; }
    public static String choose(Object list) { return "object"; }
}
''')
        def list = ExpressionDef.nullValue().cast(TypeDef.parameterized(List, TypeDef.wildcardSubtypeOf(TypeDef.STRING)))

        when:
        def call = ClassTypeDef.of(target).invokeStatic("choose", TypeDef.STRING, list)

        then:
        TypeUtils.getMethodDescriptor(null, call.method()) == '(Ljava/lang/Object;)Ljava/lang/String;'
    }

    void "source lower wildcard does not imply a numeric upper bound"() {
        given:
        def target = buildClassElement('''
package test;
class Target {
    public static <T extends Number> String choose(java.util.List<? extends T> values) { return "number"; }
    public static String choose(Object value) { return "object"; }
}
''')
        def argument = ExpressionDef.nullValue().cast(TypeDef.parameterized(List,
            TypeDef.wildcardSupertypeOf(TypeDef.of(Integer))))

        when:
        def call = ClassTypeDef.of(target).invokeStatic('choose', TypeDef.STRING, argument)

        then:
        TypeUtils.getMethodDescriptor(null, call.method()) == '(Ljava/lang/Object;)Ljava/lang/String;'
    }

    void "source upper constraints preserve conflicting parameterizations"() {
        given:
        def target = buildClassElement('''
package test;
class Target {
    public static <T extends java.util.List<String>> String choose(java.util.List<? super T> values) { return "list"; }
    public static String choose(Object value) { return "object"; }
}
''')
        def argument = ExpressionDef.nullValue().cast(TypeDef.parameterized(List, TypeDef.parameterized(List, Integer)))

        when:
        def call = ClassTypeDef.of(target).invokeStatic('choose', TypeDef.STRING, argument)

        then:
        TypeUtils.getMethodDescriptor(null, call.method()) == '(Ljava/lang/Object;)Ljava/lang/String;'
    }

    private static String writtenDescriptor(ExpressionDef call) {
        def owner = call instanceof ExpressionDef.InvokeInstanceMethod ? call.instance().type() : call.classDef()
        String descriptor = TypeUtils.getMethodDescriptor(null, call.method())
        def declared = DeclaredReturns.of(owner, call.method(), descriptor, call.method().getReturnType(), null)
        declared == null ? descriptor : descriptor.substring(0, descriptor.indexOf(')') + 1) + TypeUtils.getDescriptor(declared, null)
    }

    private static ClassTypeDef list(TypeDef argument) {
        TypeDef.parameterized(List, argument)
    }
}
