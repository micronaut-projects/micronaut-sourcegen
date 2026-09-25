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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.javapoet.AnnotationSpec;
import io.micronaut.sourcegen.javapoet.MethodSpec;
import io.micronaut.sourcegen.javapoet.TypeVariableName;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.micronaut.sourcegen.JavaTypes.ownerOf;

/**
 * The checked exceptions of the Java source generator. The bytecode writers have no notion of them: a method throws
 * what its body throws, whatever it declares, and a handler catches what reaches it. Java checks them statically,
 * which the source meets by throwing undeclared exceptions through a generic helper the compiler cannot check - the
 * same exception, thrown as the bytecode throws it - and by dispatching a catch javac would reject with
 * {@code instanceof}.
 *
 * @since 2.3
 */
@Internal
final class JavaExceptionRules {

    /**
     * The name of the helper that throws an exception unchecked.
     */
    static final String SNEAKY_THROW = "$sneakyThrow";

    private final JavaWriteContext context;
    // The top level definition being written, which declares the helper where it is used
    private final ObjectDef topLevel;
    private boolean sneakyThrow;

    /**
     * @param context  The context of the file being written
     * @param topLevel The top level definition of the file
     */
    JavaExceptionRules(JavaWriteContext context, ObjectDef topLevel) {
        this.context = context;
        this.topLevel = topLevel;
    }

    /**
     * @return Whether the source written so far throws through the helper
     */
    boolean usesSneakyThrow() {
        return sneakyThrow;
    }

    /**
     * Records that the source throws through the helper.
     *
     * @return The definition declaring the helper
     */
    ObjectDef useSneakyThrow() {
        sneakyThrow = true;
        return topLevel;
    }

    /**
     * @param scope     The scope a statement is written in
     * @param exception A checked exception
     * @return Whether the statement may throw it: the method or lambda the scope belongs to declares it, or a try
     * catches it
     */
    boolean handledIn(RenderScope scope, TypeDef exception) {
        return scope.handles(handled -> handles(handled, exception));
    }

    /**
     * @param type A type
     * @return Whether it is a checked exception class: a {@link Throwable} that is no {@link RuntimeException} or
     * {@link Error}
     */
    boolean isChecked(TypeDef type) {
        return TypeHierarchy.unwrap(type) instanceof ClassTypeDef classType
            && TypeHierarchy.inherits(classType, Throwable.class.getName(), context.elementLookup())
            && !TypeHierarchy.inherits(classType, RuntimeException.class.getName(), context.elementLookup())
            && !TypeHierarchy.inherits(classType, Error.class.getName(), context.elementLookup());
    }

    /**
     * @param type      A type
     * @param supertype Another type
     * @return Whether the type is the other or a subtype of it
     */
    boolean isSubtype(TypeDef type, TypeDef supertype) {
        return TypeHierarchy.unwrap(type) instanceof ClassTypeDef classType
            && TypeHierarchy.unwrap(supertype) instanceof ClassTypeDef superClassType
            && TypeHierarchy.inherits(classType, TypeOperations.rawClass(superClassType).getName(), context.elementLookup());
    }

    /**
     * @param handled The exceptions handled - declared or caught
     * @param thrown  An exception thrown
     * @return Whether one of those handled is the exception or a supertype of it
     */
    boolean handles(List<TypeDef> handled, TypeDef thrown) {
        return handled.stream().anyMatch(type -> isSubtype(thrown, type));
    }

    /**
     * The catches of a try that are not dead: one of a subclass of an exception an earlier one catches never catches
     * an exception, which javac rejects, nor one of a checked exception the body cannot throw.
     *
     * @param aTry The try
     * @return The catches that can catch an exception
     */
    List<StatementDef.Try.Catch> liveCatches(StatementDef.Try aTry) {
        // Nor one of a checked exception where the body calls no method and throws nothing
        boolean mayThrow = mayThrow(aTry.statement());
        List<StatementDef.Try.Catch> live = new ArrayList<>();
        for (StatementDef.Try.Catch aCatch : aTry.catches()) {
            if (live.stream().noneMatch(earlier -> isSubtype(aCatch.exception(), earlier.exception()))
                && (mayThrow || !isChecked(aCatch.exception()) || isSubtype(TypeDef.of(Exception.class), aCatch.exception()))) {
                live.add(aCatch);
            }
        }
        return live;
    }

    /**
     * Whether a statement can throw a checked exception at runtime, which only a method it calls - declaring it or
     * not - or a throw statement does: not a lambda it creates.
     *
     * @param statement The statement
     * @return true if it calls a method or throws
     */
    static boolean mayThrow(StatementDef statement) {
        boolean[] found = {false};
        JavaLambdaRules.forEachStatement(List.of(statement), child -> found[0] |= child instanceof StatementDef.Throw
            || child instanceof StatementDef.InvokeSuperConstructor);
        JavaLambdaRules.forEachExpression(List.of(statement), expression -> found[0] |= switch (expression) {
            case ExpressionDef.InvokeInstanceMethod _, ExpressionDef.InvokeStaticMethod _, ExpressionDef.NewInstance _,
                 ExpressionDef.GetPropertyValue _, ExpressionDef.InvokeHashCodeMethod _ -> true;
            case ExpressionDef.EqualsStructurally equals -> !equals.instance().type().isPrimitive() && !equals.other().type().isPrimitive();
            case ExpressionDef.NotEqualsStructurally notEquals -> !notEquals.instance().type().isPrimitive() && !notEquals.other().type().isPrimitive();
            default -> false;
        });
        return found[0];
    }

    /**
     * Whether javac accepts a catch of an exception: one that is not checked, {@link Exception} and its supertypes,
     * and one that is a subtype or a supertype of a checked exception the body of the try throws.
     *
     * @param caught The exception caught
     * @param thrown The checked exceptions the body of the try throws
     * @return true if the catch compiles
     */
    boolean isCatchable(TypeDef caught, List<TypeDef> thrown) {
        return !isChecked(caught) || isSubtype(TypeDef.of(Exception.class), caught)
            || thrown.stream().anyMatch(type -> isSubtype(type, caught) || isSubtype(caught, type));
    }

    /**
     * The checked exceptions javac sees a body throw and not catch - by the methods it calls, and by its throw
     * statements where asked to: not those of the lambdas in it.
     *
     * @param statements      The statements of the body
     * @param throwStatements Whether the exceptions thrown by throw statements count
     * @param objectDef       The definition being written
     * @param methodDef       The method being written
     * @return The exceptions
     */
    List<TypeDef> thrown(List<StatementDef> statements,
                                boolean throwStatements,
                                @Nullable ObjectDef objectDef,
                                @Nullable MethodDef methodDef) {
        List<TypeDef> thrown = new ArrayList<>();
        statements.forEach(statement -> collectThrown(statement, throwStatements, objectDef, methodDef, thrown));
        return thrown;
    }

    private void collectThrown(@Nullable StatementDef statement,
                                      boolean throwStatements,
                                      @Nullable ObjectDef objectDef,
                                      @Nullable MethodDef methodDef,
                                      List<TypeDef> thrown) {
        switch (statement) {
            case null -> {
            }
            case StatementDef.Try aTry -> {
                List<TypeDef> body = new ArrayList<>();
                collectThrown(aTry.statement(), true, objectDef, methodDef, body);
                List<StatementDef.Try.Catch> catches = liveCatches(aTry);
                // A catch javac would reject is dispatched from a catch of any throwable, which leaves nothing
                boolean dispatched = catches.stream().anyMatch(aCatch -> !isCatchable(aCatch.exception(), body));
                List<TypeDef> caught = catches.stream().<TypeDef>map(StatementDef.Try.Catch::exception).toList();
                if (!dispatched) {
                    List<TypeDef> escaping = throwStatements ? body : new ArrayList<>();
                    if (!throwStatements) {
                        collectThrown(aTry.statement(), false, objectDef, methodDef, escaping);
                    }
                    escaping.stream().filter(type -> !handles(caught, type)).forEach(thrown::add);
                }
                catches.forEach(aCatch -> collectThrown(aCatch.statement(), throwStatements, objectDef, methodDef, thrown));
                collectThrown(aTry.finallyStatement(), throwStatements, objectDef, methodDef, thrown);
            }
            case StatementDef.Multi multi -> multi.statements().forEach(child -> collectThrown(child, throwStatements, objectDef, methodDef, thrown));
            case StatementDef.If anIf -> {
                collectThrownBy(anIf.condition(), objectDef, methodDef, thrown);
                collectThrown(anIf.statement(), throwStatements, objectDef, methodDef, thrown);
            }
            case StatementDef.IfElse ifElse -> {
                collectThrownBy(ifElse.condition(), objectDef, methodDef, thrown);
                collectThrown(ifElse.statement(), throwStatements, objectDef, methodDef, thrown);
                collectThrown(ifElse.elseStatement(), throwStatements, objectDef, methodDef, thrown);
            }
            case StatementDef.Switch aSwitch -> {
                collectThrownBy(aSwitch.expression(), objectDef, methodDef, thrown);
                aSwitch.cases().values().forEach(aCase -> collectThrown(aCase, throwStatements, objectDef, methodDef, thrown));
                collectThrown(aSwitch.defaultCase(), throwStatements, objectDef, methodDef, thrown);
            }
            case StatementDef.While aWhile -> {
                collectThrownBy(aWhile.expression(), objectDef, methodDef, thrown);
                collectThrown(aWhile.statement(), throwStatements, objectDef, methodDef, thrown);
            }
            case StatementDef.Synchronized aSynchronized -> {
                collectThrownBy(aSynchronized.monitor(), objectDef, methodDef, thrown);
                collectThrown(aSynchronized.statement(), throwStatements, objectDef, methodDef, thrown);
            }
            case StatementDef.Throw aThrow -> {
                collectThrownBy(aThrow.expression(), objectDef, methodDef, thrown);
                TypeDef type = context.conversions().sourceTypeOf(aThrow.expression(), methodDef, objectDef);
                if (throwStatements && isChecked(type)) {
                    thrown.add(type);
                }
            }
            case StatementDef.InvokeSuperConstructor invocation -> {
                thrown.addAll(checked(declaredThrows(ownerOf(objectDef, invocation.superInstance().type()), invocation.method(), objectDef)));
                invocation.values().forEach(value -> collectThrownBy(value, objectDef, methodDef, thrown));
            }
            case ExpressionDef expression -> collectThrownBy(expression, objectDef, methodDef, thrown);
            default -> statement.nestedExpressionsStream().forEach(expression -> collectThrownBy(expression, objectDef, methodDef, thrown));
        }
    }

    /**
     * The checked exceptions the calls of an expression throw, and the statements of the blocks of its switch
     * expressions - not the bodies of its lambdas.
     */
    private void collectThrownBy(ExpressionDef expression,
                                        @Nullable ObjectDef objectDef,
                                        @Nullable MethodDef methodDef,
                                        List<TypeDef> thrown) {
        switch (expression) {
            case ExpressionDef.Lambda _ -> {
                return;
            }
            case ExpressionDef.SwitchYieldCase block -> {
                collectThrown(block.statement(), true, objectDef, methodDef, thrown);
                return;
            }
            case ExpressionDef.InvokeInstanceMethod invocation ->
                thrown.addAll(checked(declaredThrows(ownerOf(objectDef, invocation.instance().type()), invocation.method(), objectDef)));
            case ExpressionDef.InvokeStaticMethod invocation ->
                thrown.addAll(checked(declaredThrows(invocation.classDef(), invocation.method(), objectDef)));
            case ExpressionDef.NewInstance instance ->
                thrown.addAll(checked(declaredThrows(instance.type(), MethodDef.constructor().addParameters(instance.parameterTypes()).build(), objectDef)));
            default -> {
            }
        }
        expression.nestedExpressionsStream().forEach(nested -> collectThrownBy(nested, objectDef, methodDef, thrown));
    }

    private List<TypeDef> checked(List<TypeDef> types) {
        return types.stream().filter(this::isChecked).toList();
    }

    /**
     * The exceptions a method declares: a generated one those of its definition, a compiled one those of its class -
     * without a variable, which the call infers.
     *
     * @param owner     The type declaring the method, or {@code null}
     * @param method    The method of the model
     * @param objectDef The definition being written
     * @return The exceptions
     */
    private List<TypeDef> declaredThrows(@Nullable ClassTypeDef owner, MethodDef method, @Nullable ObjectDef objectDef) {
        ObjectDef definition = context.scope().definitionOf(owner, objectDef);
        if (definition != null) {
            List<String> erasures = method.getParameters().stream().map(parameter -> TypeHierarchy.erasedName(parameter.getType())).toList();
            for (MethodDef declared : definition.getMethods()) {
                if (declared.getName().equals(method.getName()) && erasures.equals(declared.getParameters().stream()
                    .map(parameter -> TypeHierarchy.erasedName(parameter.getType())).toList())) {
                    return declared.getThrowTypes();
                }
            }
            return method.getThrowTypes();
        }
        Executable executable = JavaSignatures.executable(owner, method);
        if (executable == null) {
            return method.getThrowTypes();
        }
        return Arrays.stream(executable.getGenericExceptionTypes())
            .filter(type -> !(type instanceof java.lang.reflect.TypeVariable<?>))
            .map(TypeHierarchy::typeDefOf)
            .toList();
    }

    /**
     * The exceptions the method a lambda implements declares, which its body may throw.
     *
     * @param functionalType The functional interface
     * @return The exceptions
     */
    List<TypeDef> functionalThrows(ClassTypeDef functionalType) {
        ObjectDef definition = context.scope().definitionOf(functionalType);
        if (definition instanceof InterfaceDef interfaceDef) {
            return interfaceDef.getMethods().stream()
                .filter(method -> method.getModifiers().contains(javax.lang.model.element.Modifier.ABSTRACT)
                    || method.getStatements().isEmpty() && !method.getModifiers().contains(javax.lang.model.element.Modifier.STATIC)
                    && !method.getModifiers().contains(javax.lang.model.element.Modifier.DEFAULT))
                .findFirst().map(MethodDef::getThrowTypes).orElse(List.of());
        }
        Class<?> type = JavaTypes.loaded(functionalType, context.scope().typeLookup());
        if (type == null) {
            return List.of();
        }
        for (Method method : type.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && !isObjectMethod(method)) {
                return Arrays.stream(method.getGenericExceptionTypes())
                    .filter(exception -> !(exception instanceof java.lang.reflect.TypeVariable<?>))
                    .map(TypeHierarchy::typeDefOf).toList();
            }
        }
        return List.of();
    }

    private static boolean isObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Whether a statement is the constructor invocation a constructor starts with, which no try can enclose.
     *
     * @param statement The statement
     * @return true if it invokes a constructor
     */
    static boolean isConstructorInvocation(StatementDef statement) {
        return statement instanceof StatementDef.InvokeSuperConstructor
            || statement instanceof ExpressionDef.InvokeInstanceMethod invocation && invocation.method().isConstructor();
    }

    /**
     * The helper that throws an exception unchecked: its variable, which only a throws clause names, is inferred as
     * {@link RuntimeException}.
     *
     * @return The helper
     */
    static MethodSpec sneakyThrowHelper() {
        TypeVariableName variable = TypeVariableName.get("T", Throwable.class);
        return MethodSpec.methodBuilder(SNEAKY_THROW)
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "$S", "unchecked").build())
            .addModifiers(javax.lang.model.element.Modifier.PRIVATE, javax.lang.model.element.Modifier.STATIC)
            .addTypeVariable(variable)
            .returns(RuntimeException.class)
            .addParameter(Throwable.class, "throwable")
            .addException(variable)
            .addStatement("throw ($T) throwable", variable)
            .build();
    }
}
