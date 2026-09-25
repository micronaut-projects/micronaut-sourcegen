/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.bytecode.tck;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The support shared by every topic of the bytecode writer TCK: writing definitions with the backend
 * under test, loading them, and calling them next to javac's own call of a compiled fixture.
 *
 * <p>A backend runs a topic by extending it and implementing {@link #write(ObjectDef)}.
 *
 * @since 2.3
 */
public abstract class AbstractByteCodeWriterTck {

    /**
     * Writes one definition to class file bytes with the backend under test.
     *
     * <p>An implementation should assert whatever its backend guarantees about how the bytes were
     * produced, such as verifying them or refusing a fallback path, so that a backend cannot pass
     * the TCK by quietly declining to write the definition itself.
     *
     * @param definition The definition to write
     * @return The class file bytes
     */
    protected abstract byte[] write(ObjectDef definition);

    /**
     * Writes and loads one definition in a class loader of its own.
     *
     * @param definition The definition to write and load
     * @return The loaded class
     * @throws ClassNotFoundException If the class cannot be loaded
     */
    protected final Class<?> define(ObjectDef definition) throws ClassNotFoundException {
        return load(definition).loadClass(definition.getName());
    }

    /**
     * Writes definitions into one class loader, so that they can refer to each other.
     *
     * @param definitions The definitions to write
     * @return The class loader that defines them
     */
    protected final ClassLoader load(ObjectDef... definitions) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (ObjectDef definition : definitions) {
            classes.put(definition.getName(), write(definition));
        }
        return new GeneratedClassLoader(classes);
    }

    /**
     * Defines a class in the package and the class loader of the fixtures, which reaches their
     * package-private members. The definition must be named in this package.
     *
     * @param definition The definition to write and define
     * @return The defined class
     * @throws IllegalAccessException If the class cannot be defined in this package
     */
    protected final Class<?> defineInPackage(ObjectDef definition) throws IllegalAccessException {
        return MethodHandles.lookup().defineClass(write(definition));
    }

    /**
     * Generates a static {@code call} method taking parameters of the given types, invokes it with
     * the given values and asserts its result. The expected value is usually javac's own call of
     * the same fixture, so that the generated call must choose what javac chooses.
     *
     * @param expected The expected result
     * @param types    The parameter types of the generated method
     * @param values   The arguments to call it with
     * @param body     The expression the method returns, given its parameters
     * @throws Exception If the class cannot be written, loaded or called
     */
    protected final void assertCall(Object expected, List<TypeDef> types, List<?> values,
                                    Function<List<ExpressionDef>, ExpressionDef> body) throws Exception {
        var method = MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.OBJECT);
        for (int i = 0; i < types.size(); i++) {
            method.addParameter("p" + i, types.get(i));
        }
        var definition = ClassDef.builder("test.tck.Caller").addModifiers(Modifier.PUBLIC)
            .addMethod(method.build((self, p) -> body.apply(new ArrayList<>(p)).returning())).build();
        var call = Arrays.stream(define(definition).getDeclaredMethods()).filter(m -> m.getName().equals("call")).findFirst().orElseThrow();
        assertEquals(expected, call.invoke(null, values.toArray()));
    }

    /**
     * Generates a static {@code call} method that returns the expression, loads it next to its
     * dependencies and calls it.
     *
     * @param expression   The expression to return
     * @param dependencies The definitions the expression refers to
     * @return The value of the expression
     * @throws Exception If the classes cannot be written, loaded or called
     */
    protected final Object run(ExpressionDef expression, ObjectDef... dependencies) throws Exception {
        return run(caller(expression), dependencies);
    }

    /**
     * Loads a caller made by {@link #caller(ExpressionDef)} next to its dependencies and calls it.
     *
     * @param caller       The caller
     * @param dependencies The definitions the caller refers to
     * @return The result of the call
     * @throws Exception If the classes cannot be written, loaded or called
     */
    protected final Object run(ClassDef caller, ObjectDef... dependencies) throws Exception {
        var definitions = new ArrayList<ObjectDef>(List.of(dependencies));
        definitions.add(caller);
        return load(definitions.toArray(ObjectDef[]::new)).loadClass(caller.getName()).getMethod("call").invoke(null);
    }

    /**
     * Writes and loads a class with one instance method {@code run}.
     *
     * @param name       The class name
     * @param returns    The return type of the method
     * @param parameters The parameter types of the method
     * @param body       The body of the method
     * @return The loaded method
     * @throws Exception If the class cannot be written or loaded
     */
    protected final Method run(String name, TypeDef returns, List<TypeDef> parameters,
                               BiFunction<VariableDef.This, List<VariableDef.MethodParameter>, StatementDef> body) throws Exception {
        MethodDef.MethodDefBuilder method = MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(returns);
        for (int i = 0; i < parameters.size(); i++) {
            method.addParameter("p" + i, parameters.get(i));
        }
        ClassDef definition = ClassDef.builder(name).addModifiers(Modifier.PUBLIC)
            .addMethod(method.build(body::apply)).build();
        Class<?> type = define(definition);
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals("run")) {
                return candidate;
            }
        }
        throw new IllegalStateException("No run method");
    }

    /**
     * Invokes a method loaded by {@link #run(String, TypeDef, List, BiFunction)} on a new instance.
     *
     * @param run       The method
     * @param arguments The arguments
     * @return The result
     * @throws Exception If the call fails
     */
    protected static Object invoke(Method run, Object... arguments) throws Exception {
        return run.invoke(run.getDeclaringClass().getConstructor().newInstance(), arguments);
    }

    /**
     * A class with a static {@code call} method that returns the expression.
     *
     * @param expression The expression
     * @return The class
     */
    protected static ClassDef caller(ExpressionDef expression) {
        return ClassDef.builder("test.tck.Caller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(expression.type()).build((self, p) -> expression.returning())).build();
    }

    /**
     * A class with a static {@code call} method that takes one parameter and returns the body.
     *
     * @param parameter The parameter type
     * @param body      The returned expression, given the parameter
     * @return The class
     */
    protected static ClassDef callWithParameter(TypeDef parameter, Function<ExpressionDef, ExpressionDef> body) {
        return ClassDef.builder("test.tck.Caller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("input", parameter).returns(TypeDef.OBJECT)
                .build((self, p) -> body.apply(p.getFirst()).returning())).build();
    }

    /**
     * A public static overload that identifies itself by the value it returns.
     *
     * @param name      The method name
     * @param parameter The parameter type
     * @param value     The value the overload returns
     * @return The method
     */
    protected static MethodDef selection(String name, TypeDef parameter, String value) {
        return selection(name, parameter, value, Modifier.PUBLIC);
    }

    /**
     * A static overload that identifies itself by the value it returns.
     *
     * @param name      The method name
     * @param parameter The parameter type
     * @param value     The value the overload returns
     * @param access    The access modifier of the overload
     * @return The method
     */
    protected static MethodDef selection(String name, TypeDef parameter, String value, Modifier access) {
        return MethodDef.builder(name).addModifiers(access, Modifier.STATIC).addParameter("value", parameter).returns(TypeDef.STRING)
            .build((self, p) -> ExpressionDef.constant(value).returning());
    }

    /**
     * @param argument The type argument
     * @return {@code List<argument>}
     */
    protected static ClassTypeDef list(TypeDef argument) {
        return TypeDef.parameterized(List.class, argument);
    }

    /**
     * The declared methods of a class as javac writes them: name, erased descriptor, access, bridges and defaults.
     *
     * @param type The class
     * @return One line per method, sorted
     */
    protected static List<String> shape(Class<?> type) {
        int mask = java.lang.reflect.Modifier.PUBLIC | java.lang.reflect.Modifier.PROTECTED | java.lang.reflect.Modifier.PRIVATE
            | java.lang.reflect.Modifier.STATIC | java.lang.reflect.Modifier.FINAL | java.lang.reflect.Modifier.SYNCHRONIZED
            | java.lang.reflect.Modifier.ABSTRACT;
        return Arrays.stream(type.getDeclaredMethods()).filter(method -> !method.getName().startsWith("lambda$"))
            .map(method -> java.lang.reflect.Modifier.toString(method.getModifiers() & mask) + " "
                + render(method.getReturnType(), type) + " " + method.getName()
                + Arrays.stream(method.getParameterTypes()).map(parameter -> render(parameter, type)).toList()
                + " throws " + Arrays.stream(method.getExceptionTypes()).map(Class::getName).toList()
                + (method.isBridge() ? " bridge" : "") + (method.isSynthetic() ? " synthetic" : "")
                + (method.isDefault() ? " default" : ""))
            .sorted().toList();
    }

    /**
     * The generic declarations of the methods of a class, its own name read as SELF.
     *
     * @param type The class
     * @return One line per method, sorted
     */
    protected static List<String> genericShape(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods()).filter(method -> !method.getName().startsWith("lambda$"))
            .map(method -> method.toGenericString().replace(type.getName(), "SELF")
                + (method.isBridge() ? " bridge" : "") + " variables=" + method.getTypeParameters().length)
            .sorted().toList();
    }

    /**
     * The generic supertypes of a class, its own name read as SELF.
     *
     * @param type The class
     * @return The superclass and interfaces
     */
    protected static String classShape(Class<?> type) {
        return (type.getGenericSuperclass() + " " + Arrays.toString(type.getGenericInterfaces())).replace(type.getName(), "SELF");
    }

    /**
     * What a call returns, or the name of the exception it throws.
     *
     * @param call The call
     * @return The result or the exception class name
     */
    protected static Object outcome(Callable<?> call) {
        try {
            return call.call();
        } catch (InvocationTargetException e) {
            return e.getCause().getClass().getName();
        } catch (Throwable e) {
            return e.getClass().getName();
        }
    }

    private static String render(Class<?> type, Class<?> self) {
        return type == self ? "SELF" : type.getName();
    }
}
