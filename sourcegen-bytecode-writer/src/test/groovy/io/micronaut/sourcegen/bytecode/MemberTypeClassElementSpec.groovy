package io.micronaut.sourcegen.bytecode

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.sourcegen.bytecode.core.SignatureUtils
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.TypeHierarchy

/** A member class of a parameterized enclosing type keeps the enclosing type's arguments. */
class MemberTypeClassElementSpec extends AbstractTypeElementSpec {

    void "source member types keep the arguments of every enclosing type"() {
        given:
        def target = buildClassElement('''
package test;
class SourceSignatures<X> {
    public Outer<String>.Member<Integer>.Nested<Long> nested() { return null; }
    public Outer<X>.Member<Integer> variable() { return null; }
    public Outer<? extends Number>.PlainMember wildcard() { return null; }
    public Outer<String>.Member<Integer>[] array() { return null; }
}
class Outer<T> {
    public class Member<U> {
        public class Nested<V> { }
    }
    public class PlainMember { }
}
''')
        def sourceMethod = target.getEnclosedElements(ElementQuery.ALL_METHODS.named(name)).first()
        def method = MethodDef.builder(name)
            .returns(TypeDef.of(sourceMethod.genericReturnType, { ignored -> null }, false)).build()

        expect:
        SignatureUtils.getMethodSignature(null, method) == expected

        where:
        name       | expected
        'nested'   | '()Ltest/Outer<Ljava/lang/String;>.Member<Ljava/lang/Integer;>.Nested<Ljava/lang/Long;>;'
        'variable' | '()Ltest/Outer<Ljava/lang/Object;>.Member<Ljava/lang/Integer;>;'
        'wildcard' | '()Ltest/Outer<+Ljava/lang/Number;>.PlainMember;'
        'array'    | '()[Ltest/Outer<Ljava/lang/String;>.Member<Ljava/lang/Integer;>;'
    }

    void "a variable of the enclosing type is substituted"() {
        given:
        def target = buildClassElement('''
package test;
class SourceSignatures<X> {
    public Outer<X>.Member<Integer> variable() { return null; }
}
class Outer<T> {
    public class Member<U> { }
}
''')
        def sourceMethod = target.getEnclosedElements(ElementQuery.ALL_METHODS.named('variable')).first()
        def type = TypeDef.of(sourceMethod.genericReturnType, { ignored -> null }, false)
        def substituted = TypeHierarchy.substituted(type, [X: TypeDef.of(String)])
        def method = MethodDef.builder('variable').returns(substituted).build()

        expect:
        type instanceof ClassTypeDef.Parameterized
        // The model keeps the element's type; the enclosing type's arguments are read from the element
        (type as ClassTypeDef.Parameterized).rawType() instanceof ClassTypeDef.ClassElementType
        TypeHierarchy.enclosingOf((type as ClassTypeDef.Parameterized).rawType()) != null
        type != substituted
        SignatureUtils.getMethodSignature(null, method) == '()Ltest/Outer<Ljava/lang/String;>.Member<Ljava/lang/Integer;>;'
    }

    void "source member signatures retain the enclosing type arguments"() {
        given:
        def target = buildClassElement('''
package test;
class SourceSignatures {
    public Outer<String>.Member<Integer> member() { return null; }
    public Outer<String>.PlainMember plainMember() { return null; }
}
class Outer<T> {
    public class Member<U> { }
    public class PlainMember { }
}
''')
        def sourceMethod = target.getEnclosedElements(ElementQuery.ALL_METHODS.named(name)).first()
        def method = MethodDef.builder(name)
            .returns(TypeDef.of(sourceMethod.genericReturnType, { ignored -> null }, false)).build()

        expect:
        SignatureUtils.getMethodSignature(null, method) == expected

        where:
        name          | expected
        'member'      | '()Ltest/Outer<Ljava/lang/String;>.Member<Ljava/lang/Integer;>;'
        'plainMember' | '()Ltest/Outer<Ljava/lang/String;>.PlainMember;'
    }

    void "source member signature retains dollar in its simple name"() {
        given:
        def target = buildClassElement('''
package test;
class Target {
    public Outer<String>.Dollar$Member<Integer> value() { return null; }
}
class Outer<T> {
    public class Dollar$Member<U> { }
}
''')
        def declared = target.getEnclosedElements(ElementQuery.ALL_METHODS.named('value')).first()
        def method = MethodDef.builder('value')
            .returns(TypeDef.of(declared.genericReturnType, { ignored -> null }, false)).build()

        expect:
        SignatureUtils.getMethodSignature(null, method) == '()Ltest/Outer<Ljava/lang/String;>.Dollar$Member<Ljava/lang/Integer;>;'
    }
}
