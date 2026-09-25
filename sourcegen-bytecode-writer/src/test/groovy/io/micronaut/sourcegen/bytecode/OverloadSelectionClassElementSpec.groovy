package io.micronaut.sourcegen.bytecode

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.sourcegen.bytecode.core.TypeUtils
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef

/**
 * Overload selection among methods whose declarations exist only as compiler elements, in source
 * compiled during annotation processing: the generated call must choose what javac chooses.
 */
class OverloadSelectionClassElementSpec extends AbstractTypeElementSpec {

    void "source intersection bounds exclude an inapplicable overload"() {
        given:
        def target = buildClassElement('''
package test;
class SourceIntersection {
    public static <T extends Number & Runnable> String choose(T value) { return "intersection"; }
    public static String choose(Object value) { return "object"; }
}
''')

        when:
        def call = ClassTypeDef.of(target).invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant(7))

        then:
        TypeUtils.getMethodDescriptor(null, call.method()) == '(Ljava/lang/Object;)Ljava/lang/String;'
    }

    void "source overloads choose the most specific reference parameter"() {
        given:
        def target = buildClassElement('''
package test;
class SourceOverloads {
    public static String choose(Object value) { return "object"; }
    public static String choose(CharSequence value) { return "sequence"; }
}
''')

        when:
        def invocation = ClassTypeDef.of(target).invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant("abc"))

        then:
        TypeUtils.getMethodDescriptor(null, invocation.method()) == '(Ljava/lang/CharSequence;)Ljava/lang/String;'
    }

    void "source overloads prefer widening to boxing"() {
        given:
        def target = buildClassElement('''
package test;
class SourceNumericOverloads {
    public static String choose(long value) { return "long"; }
    public static String choose(Integer value) { return "boxed"; }
}
''')

        when:
        def invocation = ClassTypeDef.of(target).invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant(1))

        then:
        TypeUtils.getMethodDescriptor(null, invocation.method()) == '(J)Ljava/lang/String;'
    }

    void "a secondary intersection bound can be an uncompiled source interface"() {
        given:
        def bound = buildClassElement('''
package test;
interface SourceSecondBound {
    String choose(String value);
}
''')
        def variable = TypeDef.variable("T", TypeDef.of(Runnable), ClassTypeDef.of(bound))
        def enclosing = MethodDef.builder("call").addTypeVariable(variable).returns(TypeDef.STRING).build()
        def invoked = MethodDef.builder("choose").addParameter("value", TypeDef.STRING).returns(TypeDef.STRING).build()

        when:
        def receiver = TypeUtils.receiverOf(TypeDef.variable("T"), invoked, null, enclosing)

        then:
        TypeUtils.getDescriptor(receiver.type(), null) == 'Ltest/SourceSecondBound;'
        receiver.cast()
    }

    void "source dependent method bounds determine specificity"() {
        given:
        def target = buildClassElement('''
package test;
class Chain {
    public static <A extends B, B extends Number> String choose(A value) { return "number"; }
    public static String choose(Object value) { return "object"; }
}
''')

        when:
        def call = ClassTypeDef.of(target).invokeStatic("choose", TypeDef.STRING, ExpressionDef.constant(7))

        then:
        TypeUtils.getMethodDescriptor(null, call.method()) == '(Ljava/lang/Number;)Ljava/lang/String;'
    }

    void "source secondary intersection bound determines most specific overload"() {
        given:
        def target = buildClassElement('''
package test;
class Target {
    public static <T extends java.io.Serializable & Runnable> String choose(T value) { return "both"; }
    public static String choose(Runnable value) { return "runnable"; }
}
''')
        def argument = ExpressionDef.nullValue().cast(TypeDef.variable('T',
            TypeDef.of(Serializable), TypeDef.of(Runnable)))

        when:
        def call = ClassTypeDef.of(target).invokeStatic('choose', TypeDef.STRING, argument)

        then:
        TypeUtils.getMethodDescriptor(null, call.method()) == '(Ljava/io/Serializable;)Ljava/lang/String;'
    }
}
