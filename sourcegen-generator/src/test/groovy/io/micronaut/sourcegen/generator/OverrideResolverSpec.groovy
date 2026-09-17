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

    void "resolves a covariant generic or array return across inherited methods"() {
        given:
        def a = TypeDef.variable("T")
        def first = InterfaceDef.builder("example.Wide")
            .addTypeVariable(a)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(a).build())
            .build()
        def b = TypeDef.variable("T")
        def second = InterfaceDef.builder("example.Narrow")
            .addTypeVariable(b)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(b).build())
            .build()
        def erased = MethodDef.builder("get")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        // The wider supertype first: its return type is accepted by the narrower one's
        def classDef = ClassDef.builder("example.Covariant")
            .addSuperinterface(TypeDef.parameterized(first.asTypeDef(), wide))
            .addSuperinterface(TypeDef.parameterized(second.asTypeDef(), narrow))
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null).returnType() == narrow

        where:
        wide                                                                         | narrow
        TypeDef.parameterized(Collection, String)                                    | TypeDef.parameterized(List, String)
        TypeDef.OBJECT.array()                                                       | TypeDef.STRING.array()
        TypeDef.parameterized(ClassTypeDef.of(List), TypeDef.wildcardSubtypeOf(TypeDef.of(CharSequence))) | TypeDef.parameterized(List, String)
        ClassTypeDef.of(Cloneable)                                                   | TypeDef.STRING.array()
        ClassTypeDef.of(Cloneable).array()                                           | TypeDef.array(TypeDef.Primitive.INT, 2)
        ClassDef.builder("example.Parent").build().asTypeDef()                       | ClassDef.builder("example.Child").superclass(ClassDef.builder("example.Parent").build().asTypeDef()).build().asTypeDef()
    }

    void "resolves a generated subtype of a parameterized return type"() {
        given:
        def parent = ClassDef.builder("example.GenericParent").addTypeVariable(TypeDef.variable("T")).build()
        def wide = TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING)
        // Neither type can be loaded: the model says `StringChild` is the `GenericParent<String>`
        def narrow = ClassDef.builder("example.StringChild").superclass(wide).build().asTypeDef()
        def classDef = implementing(wide, narrow)

        expect:
        OverrideResolver.resolve(classDef, classDef.methods[0], null).returnType() == narrow
    }

    void "resolves a type variable within the bound it is declared with"() {
        given:
        def variable = TypeDef.variable("V", TypeDef.of(CharSequence))
        def classDef = implementing(TypeDef.of(CharSequence), variable, variable)

        expect:
        OverrideResolver.resolve(classDef, classDef.methods[0], null).returnType() == variable
    }

    void "resolves a type variable within every bound its declaration has"() {
        given:
        def declared = TypeDef.variable("V", TypeDef.of(CharSequence), TypeDef.of(Serializable))
        // The reference names the variable without its bounds; the declaring type has both
        def reference = TypeDef.variable("V")
        def classDef = implementing(TypeDef.of(Serializable), reference, declared)

        expect:
        OverrideResolver.resolve(classDef, classDef.methods[0], null).returnType() == reference
    }

    void "resolves two references to one type variable as the same type"() {
        given:
        def declared = TypeDef.variable("V", TypeDef.of(CharSequence))
        // One reference carries the bounds, the other does not; both are the class's `V`
        def classDef = implementing(declared, TypeDef.variable("V"), declared)

        expect:
        (OverrideResolver.resolve(classDef, classDef.methods[0], null).returnType() as TypeDef.TypeVariable).name() == "V"
    }

    /**
     * A class implementing two generic interfaces whose `get()` returns the given types.
     */
    private static ClassDef implementing(TypeDef wide, TypeDef narrow, TypeDef.TypeVariable... variables) {
        def a = TypeDef.variable("T")
        def first = InterfaceDef.builder("example.WideGet")
            .addTypeVariable(a)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(a).build())
            .build()
        def b = TypeDef.variable("T")
        def second = InterfaceDef.builder("example.NarrowGet")
            .addTypeVariable(b)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(b).build())
            .build()
        def builder = ClassDef.builder("example.BothGet")
        variables.each { builder.addTypeVariable(it) }
        return builder
            .addSuperinterface(TypeDef.parameterized(first.asTypeDef(), wide))
            .addSuperinterface(TypeDef.parameterized(second.asTypeDef(), narrow))
            .addMethod(MethodDef.builder("get")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.OBJECT)
                .overrides()
                .build())
            .build()
    }

    void "resolves nothing for a generic method whose variable shadows the supertype's"() {
        given:
        def classVariable = TypeDef.variable("T")
        def methodVariable = TypeDef.variable("T")
        // The method's `T` is its own, which `Echoes<String>` binds nothing of
        def parent = ClassDef.builder("example.Echoes")
            .addTypeVariable(classVariable)
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(methodVariable)
                .addParameter("value", methodVariable)
                .returns(methodVariable)
                .build { aThis, parameters -> parameters[0].returning() })
            .build()
        def erased = MethodDef.builder("echo")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.StringEchoes")
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING))
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null) == null
    }

    void "resolves the return type a non-generic inherited method needs as well"() {
        given:
        def variable = TypeDef.variable("T")
        def generic = InterfaceDef.builder("example.GenericGet")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(variable).build())
            .build()
        def plain = InterfaceDef.builder("example.IntegerGet")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.of(Integer)).build())
            .build()
        def erased = MethodDef.builder("get")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        // `Number get()` does not implement `IntegerGet`
        def classDef = ClassDef.builder("example.BothGets")
            .addSuperinterface(TypeDef.parameterized(generic.asTypeDef(), TypeDef.of(Number)))
            .addSuperinterface(plain.asTypeDef())
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null).returnType() == TypeDef.of(Integer)
    }

    void "resolves the class variables a generic method uses besides its own"() {
        given:
        def classVariable = TypeDef.variable("T")
        def methodVariable = TypeDef.variable("U")
        // `echo` declares `U` of its own, but its signature is in the class's `T`, which `Echoes<String>` binds
        def parent = ClassDef.builder("example.PartlyGeneric")
            .addTypeVariable(classVariable)
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(methodVariable)
                .addParameter("value", classVariable)
                .returns(classVariable)
                .build { aThis, parameters -> parameters[0].returning() })
            .build()
        def erased = MethodDef.builder("echo")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.StringPartlyGeneric")
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING))
            .addMethod(erased)
            .build()

        when:
        def overridden = OverrideResolver.resolve(classDef, erased, null)

        then:
        overridden.parameterTypes() == [TypeDef.STRING]
        overridden.returnType() == TypeDef.STRING
    }

    void "resolves a narrower non-generic return next to a bounded erasure"() {
        given:
        def variable = TypeDef.variable("T", TypeDef.of(Number))
        def bounded = InterfaceDef.builder("example.BoundedGet")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(variable).build())
            .build()
        def plain = InterfaceDef.builder("example.PlainIntegerGet")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.of(Integer)).build())
            .build()
        // The model declares the bound's erasure, which does not implement `Integer get()`
        def erased = MethodDef.builder("get")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.of(Number))
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.BoundedAndPlain")
            .addSuperinterface(TypeDef.parameterized(bounded.asTypeDef(), TypeDef.of(Number)))
            .addSuperinterface(plain.asTypeDef())
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null).returnType() == TypeDef.of(Integer)
    }

    void "resolves nothing for a method-local variable that a variable of the declaring type is named like"() {
        given:
        def methodVariable = TypeDef.variable("T")
        def parent = ClassDef.builder("example.ShadowingParent")
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("echo").addModifiers(Modifier.PUBLIC)
                .addTypeVariable(methodVariable)
                .addParameter("value", methodVariable)
                .returns(methodVariable)
                .build { aThis, parameters -> parameters[0].returning() })
            .build()
        def erased = MethodDef.builder("echo")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .overrides()
            .build()
        // The child's own `T` is not the method's `T`
        def classDef = ClassDef.builder("example.ShadowingChild")
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(CharSequence)))
            .superclass(TypeDef.parameterized(parent.asTypeDef(), TypeDef.STRING))
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null) == null
    }

    void "resolves a narrower return of a generated type next to a bounded erasure"() {
        given:
        def base = ClassDef.builder("example.BaseValue").build()
        def child = ClassDef.builder("example.ChildValue").superclass(base.asTypeDef()).build()
        def variable = TypeDef.variable("T", base.asTypeDef())
        def bounded = InterfaceDef.builder("example.BaseGet")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(variable).build())
            .build()
        def plain = InterfaceDef.builder("example.ChildGet")
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(child.asTypeDef()).build())
            .build()
        // Neither value type can be loaded: the model says `ChildValue` is a `BaseValue`
        def erased = MethodDef.builder("get")
            .addModifiers(Modifier.PUBLIC)
            .returns(base.asTypeDef())
            .overrides()
            .build()
        def classDef = ClassDef.builder("example.BothValues")
            .addSuperinterface(TypeDef.parameterized(bounded.asTypeDef(), base.asTypeDef()))
            .addSuperinterface(plain.asTypeDef())
            .addMethod(erased)
            .build()

        expect:
        OverrideResolver.resolve(classDef, erased, null).returnType() == child.asTypeDef()
    }
}
