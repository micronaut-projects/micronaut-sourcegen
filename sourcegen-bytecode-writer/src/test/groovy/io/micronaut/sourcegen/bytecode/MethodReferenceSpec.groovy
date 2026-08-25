package io.micronaut.sourcegen.bytecode

import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.InterfaceDef
import io.micronaut.sourcegen.model.JavaIdioms
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.MethodReferenceExpression
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.util.CheckClassAdapter
import spock.lang.Specification

import javax.lang.model.element.Modifier
import java.util.function.Function

/**
 * Every kind of method reference, generated as bytecode and then linked and invoked, so that the
 * {@code invokedynamic} is checked by the JVM rather than only by its shape.
 */
class MethodReferenceSpec extends Specification {

    private static final String CLASS_NAME = "example.Refs"
    private static final ClassTypeDef REFS = ClassTypeDef.of(CLASS_NAME)

    // public static String shout(String value) { return value.concat("!"); }
    private static final MethodDef SHOUT = MethodDef.builder("shout")
        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
        .addParameter("value", TypeDef.STRING)
        .returns(TypeDef.STRING)
        .build((aThis, params) -> params.get(0)
            .invoke("concat", TypeDef.STRING, ExpressionDef.constant("!")).returning())

    void "a static method reference calls the method"() {
        given:
        def function = TypeDef.parameterized(Function.class, String.class, String.class)

        when:
        def instance = generate(stringMethod("staticRef", function.staticMethodReference(REFS, SHOUT)))

        then:
        instance.staticRef("Hello") == "Hello!"
    }

    void "a parameterized interface needs no LambdaDef and still resolves its type variables"() {
        given:
        def function = TypeDef.parameterized(Function.class, String.class, String.class)

        when:
        def instance = generate(stringMethod("staticRef", function.staticMethodReference(REFS, SHOUT)))

        then:
        instance.staticRef("Hello") == "Hello!"
    }

    void "a raw interface erases its type variables and cannot link"() {
        given:
        // Contrast: the raw type leaves apply(Object)Object, which the metafactory rejects for a
        // method taking String. The call site only links when it first runs, so the class builds fine
        def raw = ClassTypeDef.of(Function.class)
        def instance = generate(stringMethod("staticRef", raw.staticMethodReference(REFS, SHOUT)))

        when:
        instance.staticRef("Hello")

        then:
        def e = thrown(Throwable)
        (e.toString() + e.cause).contains("LambdaConversionException")
    }

    void "the bound helper also takes the interface directly"() {
        given:
        def function = TypeDef.parameterized(Function.class, String.class, String.class)
        def reference = function
            .methodReference(ExpressionDef.constant("prefix_"), TypeDef.STRING.findDeclaredMethods("concat", 1).get(0))

        when:
        def instance = generate(stringMethod("boundRef", reference))

        then:
        instance.boundRef("Hello") == "prefix_Hello"
    }

    void "a reflective method reference is static when the method it names is"() {
        given:
        // MethodDef.of(Method) does not carry the static modifier, so it is read off the Method itself
        def function = TypeDef.parameterized(Function.class, ClassTypeDef.of(String.class), ClassTypeDef.of(Integer.class))
        def reference = MethodReferenceExpression.of(function, Integer.getMethod("valueOf", String))
        def result = new VariableDef.Local("result", ClassTypeDef.of(Function.class))

        // Function result = Integer::valueOf;
        // return result.apply(input).toString();
        def method = MethodDef.builder("parseRef")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("input", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build((aThis, params) -> StatementDef.multi(
                result.defineAndAssign(reference),
                apply(result, params.get(0)).invoke("toString", TypeDef.STRING).returning()
            ))

        when:
        def instance = generate(method)

        then:
        reference.isStatic()
        instance.parseRef("42") == "42"
    }

    void "a bound method reference calls the method on the captured receiver"() {
        given:
        def function = TypeDef.parameterized(Function.class, String.class, String.class)
        def reference = function.methodReference(ExpressionDef.constant("prefix_"), "concat")

        when:
        def instance = generate(stringMethod("boundRef", reference))

        then:
        reference.instance() != null
        instance.boundRef("Hello") == "prefix_Hello"
    }

    void "a bound method reference captures a local variable receiver"() {
        given:
        def function = TypeDef.parameterized(Function.class, String.class, String.class)
        def prefix = new VariableDef.Local("prefix", TypeDef.STRING)
        def result = new VariableDef.Local("result", ClassTypeDef.of(Function.class))

        // String prefix = "local_";
        // Function result = prefix::concat;
        // return (String) result.apply(input);
        def method = MethodDef.builder("capturingRef")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("input", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build((aThis, params) -> StatementDef.multi(
                prefix.defineAndAssign(ExpressionDef.constant("local_")),
                result.defineAndAssign(function.methodReference(prefix, "concat")),
                apply(result, params.get(0)).cast(TypeDef.STRING).returning()
            ))

        when:
        def instance = generate(method)

        then:
        instance.capturingRef("Hello") == "local_Hello"
    }

    void "a named method reference becomes an expression once bound"() {
        given:
        def function = TypeDef.parameterized(Function.class, String.class, String.class)
        // Naming the method needs no functional interface, and is not an expression on its own
        MethodDef named = TypeDef.STRING.findDeclaredMethods("concat", 1).get(0)
        def reference = function.methodReference(ExpressionDef.constant("named_"), named)

        when:
        def instance = generate(stringMethod("namedRef", reference))

        then:
        !(named instanceof ExpressionDef)
        reference.instance() != null
        instance.namedRef("Hello") == "named_Hello"
    }

    void "a bound method reference to an interface method uses invokeinterface"() {
        given:
        def listType = ClassTypeDef.of(List.class)
        def function = TypeDef.parameterized(Function.class, ClassTypeDef.of(String.class), ClassTypeDef.of(Boolean.class))
        def result = new VariableDef.Local("result", ClassTypeDef.of(Function.class))

        // Function result = List.of("Hello")::contains;
        // return result.apply(input).toString();
        def method = MethodDef.builder("containsRef")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("input", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build((aThis, params) -> StatementDef.multi(
                result.defineAndAssign(
                    function.methodReference(
                        listType.invokeStatic("of", [TypeDef.OBJECT], listType, [ExpressionDef.constant("Hello")]),
                        listType.findDeclaredMethods("contains", 1).get(0))),
                apply(result, params.get(0)).invoke("toString", TypeDef.STRING).returning()
            ))

        when:
        def instance = generate(method)

        then:
        instance.containsRef("Hello") == "true"
        instance.containsRef("Nope") == "false"
    }

    void "a constructor reference instantiates the type"() {
        given:
        def builderType = ClassTypeDef.of(StringBuilder.class)
        def function = TypeDef.parameterized(Function.class, TypeDef.STRING, builderType)
        // StringBuilder declares three single-argument constructors, so the one to reference is named
        def reference = MethodReferenceExpression.of(function, StringBuilder.getDeclaredConstructor(String))
        def result = new VariableDef.Local("result", ClassTypeDef.of(Function.class))

        // Function result = StringBuilder::new;
        // return ((StringBuilder) result.apply(input)).reverse().toString();
        def method = MethodDef.builder("constructorRef")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("input", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build((aThis, params) -> StatementDef.multi(
                result.defineAndAssign(reference),
                apply(result, params.get(0))
                    .cast(builderType)
                    .invoke("reverse", builderType)
                    .invoke("toString", TypeDef.STRING).returning()
            ))

        when:
        def instance = generate(method)

        then:
        reference.isConstructor()
        instance.constructorRef("Hello") == "olleH"
    }

    void "a reference to a method of the interface being generated uses invokeinterface"() {
        given:
        def selfType = ClassTypeDef.of("example.SelfRef")
        def function = TypeDef.parameterized(Function.class, String.class, String.class)
        def result = new VariableDef.Local("result", ClassTypeDef.of(Function.class))

        // default String decorate(String value) { return "self_" + value; }
        def decorate = MethodDef.builder("decorate")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build((aThis, params) -> JavaIdioms.concatStrings(ExpressionDef.constant("self_"), params.get(0)).returning())

        // default String call(String input) {
        //     Function result = this::decorate;
        //     return (String) result.apply(input);
        // }
        def call = MethodDef.builder("call")
            .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
            .addParameter("input", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build((aThis, params) -> StatementDef.multi(
                result.defineAndAssign(function.methodReference(new VariableDef.This(), decorate)),
                apply(result, params.get(0)).cast(TypeDef.STRING).returning()
            ))

        InterfaceDef interfaceDef = InterfaceDef.builder("example.SelfRef")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(decorate)
            .addMethod(call)
            .build()
        ClassDef implDef = ClassDef.builder("example.SelfRefImpl")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(selfType)
            .build()

        when:
        def loader = new MultiClassLoader(MethodReferenceSpec.classLoader)
        loader.define("example.SelfRef", toBytes(interfaceDef))
        // The reference must be linked through invokeinterface, or the default method fails to verify
        Class<?> impl = loader.define("example.SelfRefImpl", toBytes(implDef))
        def instance = impl.getDeclaredConstructor().newInstance()

        then:
        impl.getMethod("call", String).invoke(instance, "Hello") == "self_Hello"
    }

    /**
     * A method that assigns the reference to a local and applies it to its own argument:
     * {@code Function result = <reference>; return (String) result.apply(input); }
     */
    private static MethodDef stringMethod(String name, MethodReferenceExpression reference) {
        def result = new VariableDef.Local("result", ClassTypeDef.of(Function.class))
        return MethodDef.builder(name)
            .addModifiers(Modifier.PUBLIC)
            .addParameter("input", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build((aThis, params) -> StatementDef.multi(
                result.defineAndAssign(reference),
                apply(result, params.get(0)).cast(TypeDef.STRING).returning()
            ))
    }

    private static ExpressionDef apply(ExpressionDef function, ExpressionDef argument) {
        return function.invoke("apply", [TypeDef.OBJECT], TypeDef.OBJECT, [argument])
    }

    private static Object generate(MethodDef method) {
        ClassDef classDef = ClassDef.builder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(SHOUT)
            .addMethod(method)
            .build()
        def loader = new MultiClassLoader(MethodReferenceSpec.classLoader)
        return loader.define(CLASS_NAME, toBytes(classDef)).getDeclaredConstructor().newInstance()
    }

    private static byte[] toBytes(ObjectDef objectDef) {
        def classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)
        new ByteCodeWriter(false, false).writeObject(new CheckClassAdapter(classWriter), objectDef)
        return classWriter.toByteArray()
    }

    private static class MultiClassLoader extends ClassLoader {

        MultiClassLoader(ClassLoader parent) {
            super(parent)
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length)
        }
    }

}
