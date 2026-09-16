package io.micronaut.sourcegen.generator

import io.micronaut.sourcegen.model.AnnotationDef
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ParameterDef
import io.micronaut.sourcegen.model.TypeDef
import spock.lang.Specification

import javax.lang.model.element.Modifier
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

class OverrideResolverSpec extends Specification {

    void "resolves the substituted signature of an erased override"() {
        given:
        def variable = TypeDef.variable("T")
        def consumer = InterfaceDef.builder("example.Consumer")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("accept")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", variable)
                .build())
            .build()
        def erased = MethodDef.builder("accept")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.StringConsumer")
            .addSuperinterface(TypeDef.parameterized(consumer.asTypeDef(), TypeDef.STRING))
            .addMethod(erased)
            .build()

        when:
        def overridden = OverrideResolver.resolve(classDef, erased, null)

        then:
        overridden.parameterTypes() == [TypeDef.STRING]
        overridden.returnType() == TypeDef.VOID
    }

    void "resolves the substituted signature of an erased override of a reflected type"() {
        given:
        def erased = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.Length")
            .addSuperinterface(TypeDef.parameterized(Function, String, Integer))
            .addMethod(erased)
            .build()

        when:
        def overridden = OverrideResolver.resolve(classDef, erased, null)

        then:
        overridden.parameterTypes() == [TypeDef.STRING]
        overridden.returnType() == TypeDef.of(Integer)
    }

    void "resolves nothing for a method that does not override"() {
        given:
        def erased = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .build()
        def classDef = ClassDef.builder("example.Length")
            .addSuperinterface(TypeDef.parameterized(Function, String, Integer))
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null) == null
    }

    void "resolves nothing for an override that is already typed"() {
        given:
        def typed = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.Identity")
            .addSuperinterface(TypeDef.parameterized(Function, String, String))
            .addMethod(typed)
            .build()

        expect:
        OverrideResolver.resolve(classDef, typed, null) == null
    }

    void "resolves nothing through a raw supertype"() {
        given:
        def erased = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        // The type variables of a raw supertype are unbound, so the erased signature has to stay
        def classDef = ClassDef.builder("example.RawFunction")
            .addSuperinterface(ClassTypeDef.of(Function))
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null) == null
    }

    void "resolves nothing when only the type arguments differ"() {
        given:
        def names = InterfaceDef.builder("example.Names")
            .addMethod(MethodDef.builder("names")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.parameterized(Set, String))
                .build())
            .build()
        // A raw return type for a parameterized one overrides as is; no bridge, nothing to resolve
        def raw = MethodDef.builder("names")
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassTypeDef.of(Set))
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.RawNames")
            .addSuperinterface(names.asTypeDef())
            .addMethod(raw)
            .build()

        expect:
        OverrideResolver.resolve(classDef, raw, null) == null
    }

    void "resolves a return type that is a type variable of the declaring type"() {
        given:
        def variable = TypeDef.variable("T")
        def erased = MethodDef.builder("get")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        // `Object get()` erases like `T get()`, but is not a subtype of it
        def classDef = ClassDef.builder("example.Box")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier), variable))
            .addMethod(erased)
            .build()

        when:
        def overridden = OverrideResolver.resolve(classDef, erased, null)

        then:
        overridden.parameterTypes() == []
        overridden.returnType() == variable
    }

    void "resolves an erased parameter of a type variable only for an exact override"() {
        given:
        def variable = TypeDef.variable("T")
        def erased = MethodDef.builder("accept")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.Box")
            .addTypeVariable(variable)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Consumer), variable))
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null) == null
        OverrideResolver.resolve(classDef, erased, null, true).parameterTypes() == [variable]
    }

    void "resolves a raw return type only for an exact override"() {
        given:
        def names = InterfaceDef.builder("example.Names")
            .addMethod(MethodDef.builder("names")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.parameterized(Set, String))
                .build())
            .build()
        def raw = MethodDef.builder("names")
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassTypeDef.of(Set))
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.RawNames")
            .addSuperinterface(names.asTypeDef())
            .addMethod(raw)
            .build()

        expect:
        OverrideResolver.resolve(classDef, raw, null) == null
        OverrideResolver.resolve(classDef, raw, null, true).returnType() == TypeDef.parameterized(Set, String)
    }

    void "resolves a parameter named by the simple name of a member type"() {
        given:
        def variable = TypeDef.variable("T")
        def producer = InterfaceDef.builder("example.Producer")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("produce")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("key", ClassTypeDef.of("example.Outer\$Key"))
                .returns(variable)
                .build())
            .build()
        // The member type referenced by its simple name, as the definition holding it was built
        def erased = MethodDef.builder("produce")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("key", ClassTypeDef.of("Key"))
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.Outer")
            .addInnerType(ClassDef.builder("Key").build())
            .addSuperinterface(TypeDef.parameterized(producer.asTypeDef(), TypeDef.STRING))
            .addMethod(erased)
            .build()

        when:
        def overridden = OverrideResolver.resolve(classDef, erased, null)

        then:
        overridden.returnType() == TypeDef.STRING
    }

    void "applies the signature keeping what the method declares"() {
        given:
        def erased = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(Deprecated).build())
            .addJavadoc("Applies")
            .synthetic()
            .addParameter(ParameterDef.builder("value", TypeDef.OBJECT).addJavadoc("The value").synthetic().build())
            .returns(TypeDef.OBJECT)
            .overrides()
            .build { aThis, parameters -> parameters[0].returning() }
        def classDef = ClassDef.builder("example.Length")
            .addSuperinterface(TypeDef.parameterized(Function, String, Integer))
            .addMethod(erased)
            .build()

        when:
        def applied = OverrideResolver.resolve(classDef, erased, null).apply(erased)

        then:
        applied.name == "apply"
        applied.override
        applied.modifiers == [Modifier.PUBLIC] as Set
        applied.annotations == erased.annotations
        applied.javadoc == ["Applies"]
        applied.synthetic
        applied.parameters*.name == ["value"]
        applied.parameters*.type == [TypeDef.STRING]
        applied.parameters*.javadoc == [["The value"]]
        applied.parameters*.synthetic == [true]
        applied.returnType == TypeDef.of(Integer)
        applied.statements == erased.statements
    }

    void "resolves nothing through a raw supertype whose variable the declaring type names too"() {
        given:
        def erased = MethodDef.builder("get")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        // The `T` of the raw `Supplier` is bound to nothing; the class's own `T` is another variable
        def classDef = ClassDef.builder("example.RawSupplier")
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number)))
            .addSuperinterface(ClassTypeDef.of(Supplier))
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null) == null
        OverrideResolver.resolve(classDef, erased, null, true) == null
    }

    void "keeps a raw parameter where only the return type changes"() {
        given:
        def variable = TypeDef.variable("T")
        def function = InterfaceDef.builder("example.Fn")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("values", TypeDef.parameterized(ClassTypeDef.of(List), variable))
                .returns(variable)
                .build())
            .build()
        def erased = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("values", ClassTypeDef.of(List))
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.StringFn")
            .addSuperinterface(TypeDef.parameterized(function.asTypeDef(), TypeDef.STRING))
            .addMethod(erased)
            .build()

        when:
        def overridden = OverrideResolver.resolve(classDef, erased, null)

        then: 'the raw List is a valid Java override, and the body is written against it'
        overridden.returnType() == TypeDef.STRING
        overridden.parameterTypes() == [ClassTypeDef.of(List)]
    }

    void "resolves the return type every inherited method accepts"() {
        given:
        def a = TypeDef.variable("T")
        def first = InterfaceDef.builder("example.First")
            .addTypeVariable(a)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(a).build())
            .build()
        def b = TypeDef.variable("T")
        def second = InterfaceDef.builder("example.Second")
            .addTypeVariable(b)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(b).build())
            .build()
        def erased = MethodDef.builder("get")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        // `Number get()` does not implement `Second<Integer>`; `Integer get()` implements both
        def classDef = ClassDef.builder("example.Both")
            .addSuperinterface(TypeDef.parameterized(first.asTypeDef(), TypeDef.of(Number)))
            .addSuperinterface(TypeDef.parameterized(second.asTypeDef(), TypeDef.of(Integer)))
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null).returnType() == TypeDef.of(Integer)
    }
}
