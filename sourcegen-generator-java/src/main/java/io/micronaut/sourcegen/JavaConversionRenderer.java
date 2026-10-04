/*
 * Copyright 2017-2023 original authors
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
import io.micronaut.sourcegen.model.TypeOperations;
import static io.micronaut.sourcegen.JavaTypes.ownerOf;
import static io.micronaut.sourcegen.JavaCasts.isNullLiteral;
import static io.micronaut.sourcegen.JavaPrecedence.requiresReceiverParentheses;
import static io.micronaut.sourcegen.generator.OverloadRules.hasApplicableOverload;
import static io.micronaut.sourcegen.generator.OverloadRules.pinsOverload;
import static io.micronaut.sourcegen.generator.OverloadRules.receiverBound;
import org.jspecify.annotations.Nullable;
import io.micronaut.sourcegen.generator.GenerationScope;
import io.micronaut.sourcegen.generator.InvokedSignature;
import io.micronaut.sourcegen.generator.OverloadRules;
import io.micronaut.sourcegen.generator.OverrideResolver;
import io.micronaut.sourcegen.javapoet.AnnotationSpec;
import io.micronaut.sourcegen.javapoet.ArrayTypeName;
import io.micronaut.sourcegen.javapoet.CodeBlock;
import io.micronaut.sourcegen.javapoet.MethodSpec;
import io.micronaut.sourcegen.javapoet.TypeName;
import io.micronaut.sourcegen.javapoet.TypeSpec;
import io.micronaut.sourcegen.javapoet.TypeVariableName;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.VariableDef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The conversions of the Java source generator that are written as more than a cast: a method reference as a lambda
 * that converts its arguments, an array of an intersection through a generic helper, a reference unboxed through
 * {@link Number}, and the operands of a concatenation.
 *
 * @since 2.3
 */
@Internal
final class JavaConversionRenderer {

    private final JavaExpressionRenderer expressions;
    private final JavaTypeRenderer types;
    private final JavaConversionRules conversionRules;
    private final JavaPoetNames names;
    private final GenerationScope generationScope;

    JavaConversionRenderer(JavaExpressionRenderer expressions) {
        this.expressions = expressions;
        this.types = expressions.types();
        this.conversionRules = expressions.context().conversions();
        this.names = expressions.context().names();
        this.generationScope = expressions.context().scope();
    }

    /**
     * A reference as a lambda converting its arguments and result, where the reference would name another method
     * than the model, or return what the functional interface does not: to a generated method that override
     * resolution narrowed, to a less specific overload, to a static method an instance method of the name makes
     * ambiguous, and to a method whose result the metafactory casts. A receiver other than `this` or a parameter,
     * which the model never assigns, is read once and checked for `null` where the reference is created, as the
     * reference would.
     *
     * @param instance The receiver of a bound reference, or {@code null} for a static or a constructor reference
     */
    @Nullable
    CodeBlock renderAdaptedReference(@Nullable ObjectDef objectDef,
                                     @Nullable MethodDef methodDef,
                                     RenderScope scope,
                                     MethodReferenceExpression reference,
                                     @Nullable ExpressionDef instance) {
        OverrideResolver.ReferenceAdaptation adaptation = instance == null ? null : OverrideResolver.adaptReference(
            ownerOf(objectDef, instance.type()), objectDef, methodDef, reference, generationScope, false);
        if (adaptation == null) {
            adaptation = boundReference(objectDef, reference, instance);
        }
        if (adaptation == null) {
            return null;
        }
        RenderScope lambdaScope = scope.nested(null);
        // A receiver other than `this` is checked for `null` where the reference is created, as the reference would
        boolean captured = instance != null && !(instance instanceof VariableDef.This || instance instanceof VariableDef.Super);
        String receiver = captured ? lambdaScope.declareFresh("target") : "";
        List<CodeBlock> parameters = new ArrayList<>();
        List<CodeBlock> arguments = new ArrayList<>();
        for (int i = 0; i < adaptation.argumentTypes().size(); i++) {
            String name = lambdaScope.declareFresh("arg");
            parameters.add(CodeBlock.of("$L", name));
            TypeDef type = adaptation.argumentTypes().get(i);
            CodeBlock.Builder argument = CodeBlock.builder();
            if (type != null) {
                argument.add("($T) ", types.asType(type, objectDef, methodDef));
                adaptation.argumentConversions().get(i).forEach(conversion -> argument.add("($T) ", types.asType(conversion, objectDef)));
            }
            arguments.add(argument.add("$L", name).build());
        }
        CodeBlock call;
        if (instance == null) {
            ClassTypeDef owner = reference.owner();
            call = reference.isConstructor()
                ? CodeBlock.of("new $L($L)", expressions.renderInstantiated(owner, objectDef, methodDef), CodeBlock.join(arguments, ", "))
                // A static member is named by the raw type
                : CodeBlock.of("$T.$L($L)", types.asType(TypeOperations.rawClass(owner), objectDef, methodDef),
                reference.method().getName(), CodeBlock.join(arguments, ", "));
        } else {
            call = CodeBlock.of("$L.$L($L)", captured ? receiver
                : expressions.renderExpression(objectDef, methodDef, scope, instance), reference.method().getName(),
                CodeBlock.join(arguments, ", "));
        }
        if (adaptation.resultBound() != null) {
            // Only an unchecked conversion through the bound's raw type turns the result into the variable
            call = CodeBlock.of("($T) $L", types.asType(adaptation.resultBound(), objectDef, methodDef), call);
        }
        if (adaptation.resultType() != null) {
            call = CodeBlock.of("($T) $L", types.asType(adaptation.resultType(), objectDef, methodDef), call);
        }
        CodeBlock lambda = CodeBlock.of("($L) -> $L", CodeBlock.join(parameters, ", "), call);
        if (instance == null || !captured) {
            return lambda;
        }
        TypeName functional = types.asType(reference.type(), objectDef, methodDef);
        CodeBlock read = CodeBlock.of("$T.of($L).<$T>map($L -> $L).get()", Optional.class,
            isNullLiteral(instance) ? CodeBlock.of("($T) null", types.asType(instance.type(), objectDef, methodDef))
                : expressions.renderExpression(objectDef, methodDef, scope, instance), functional, receiver, lambda);
        // A lambda returning a raw type makes `map` an unchecked invocation, whose result is erased
        return adaptation.resultType() != null && names.isRawGeneric(adaptation.resultType())
            ? CodeBlock.of("($T) $L", functional, read) : read;
    }

    /**
     * The casts a reference is written with, as a lambda, so that it names the method of the model: where the owner
     * declares another overload the functional interface would select, where the receiver binds a parameter -
     * {@code apply} of a {@code Function<String, String>} takes no {@code Object} - and where the result is not of
     * the type the functional interface returns, which the metafactory casts it to.
     */
    private OverrideResolver.@Nullable ReferenceAdaptation boundReference(@Nullable ObjectDef objectDef,
                                                                          MethodReferenceExpression reference,
                                                                          @Nullable ExpressionDef instance) {
        MethodDef method = reference.method();
        ClassTypeDef owner = instance == null ? reference.owner() : ownerOf(objectDef, instance.type());
        List<TypeDef> parameterTypes = method.getParameters().stream().map(ParameterDef::getType).toList();
        List<TypeDef> passed;
        try {
            passed = reference.type().getLambda().getImplementation().getParameters().stream().map(ParameterDef::getType).toList();
        } catch (RuntimeException e) {
            // A functional interface known only by name has no members to read
            return null;
        }
        if (passed.size() != parameterTypes.size()
            || !reference.type().getLambda().getImplementation().getTypeVariables().isEmpty()) {
            return null;
        }
        boolean overloaded = hasApplicableOverload(owner, generationScope.definitionOf(owner, objectDef), method.getName(),
            parameterTypes, new ArrayList<>(passed), generationScope);
        InvokedSignature signature = conversionRules.declaredSignature(owner, method.getName(), parameterTypes);
        Map<String, TypeDef> receiverArguments = new HashMap<>(OverrideResolver.receiverArguments(owner, objectDef, method, generationScope));
        if (receiverArguments.isEmpty() && owner != null && !(owner instanceof ClassTypeDef.Parameterized)
            && generationScope.definitionOf(owner, objectDef) == null) {
            // A raw receiver of a compiled class erases the variables of the class
            receiverArguments.putAll(OverloadRules.erasedClassVariables(owner));
        }
        List<@Nullable TypeDef> argumentTypes = new ArrayList<>();
        List<List<TypeDef>> conversions = new ArrayList<>();
        boolean converted = false;
        for (int i = 0; i < parameterTypes.size(); i++) {
            TypeDef bound = receiverBound(parameterTypes.get(i),
                signature != null && signature.parameterTypes().size() == parameterTypes.size() ? signature.parameterTypes().get(i) : null,
                method.getTypeVariables(), receiverArguments);
            boolean cast = overloaded && pinsOverload(bound, passed.get(i), method.getTypeVariables())
                || !bound.equals(parameterTypes.get(i)) && conversionRules.requiresImplicitInvocationCast(bound, passed.get(i));
            argumentTypes.add(cast ? bound instanceof ClassTypeDef.Parameterized parameterized ? parameterized.rawType() : bound : null);
            conversions.add(List.of());
            converted |= cast;
        }
        TypeDef expected = reference.type().getLambda().getImplementation().getReturnType();
        TypeDef returned = reference.isConstructor() ? null : returnedType(owner, objectDef, method, receiverArguments);
        TypeDef resultType = returned != null && !TypeDef.VOID.equals(expected) && !TypeDef.VOID.equals(returned)
            && conversionRules.requiresImplicitInvocationCast(expected, returned) ? expected : null;
        // `Integer::toString` names `toString(int)` and `toString()` of a `Function<Integer, String>`
        boolean ambiguous = reference.isStatic() && owner != null && JavaSignatures.hasInstanceMethodForReference(owner, method);
        return converted || resultType != null || ambiguous
            ? new OverrideResolver.ReferenceAdaptation(argumentTypes, conversions, resultType, null) : null;
    }

    /**
     * The type the source sees a referenced method return: the one it declares, of the receiver's type arguments,
     * or {@code null} where it names a variable of the method, which the reference infers.
     */
    @Nullable
    private TypeDef returnedType(@Nullable ClassTypeDef owner,
                                 @Nullable ObjectDef objectDef,
                                 MethodDef method,
                                 Map<String, TypeDef> receiverArguments) {
        TypeDef declared;
        if (generationScope.definitionOf(owner, objectDef) != null) {
            declared = method.getReturnType();
        } else if (JavaSignatures.executable(owner, method) instanceof java.lang.reflect.Method compiled) {
            declared = TypeHierarchy.typeDefOf(compiled.getGenericReturnType());
        } else {
            return null;
        }
        TypeDef returned = TypeHierarchy.substituted(declared, receiverArguments);
        return TypeHierarchy.containsVariableOtherThan(returned, Set.of()) ? null : returned;
    }

    /**
     * Whether a lambda of a raw type is cast to it where it is passed: the parameter of the invoked method would type
     * the lambda by the type arguments of the receiver, which the model does not - {@code Function<? super String, ?
     * extends Integer>} rejects a body returning an {@code Object}, and types a parameter a {@code String} that an
     * overload of the method it is passed to takes instead of the {@code Object} of the model.
     *
     * @param value             The lambda
     * @param declaredType      The type the invoked method declares the parameter with
     * @param receiverArguments The type arguments the receiver binds the variables of its class with
     * @param objectDef         The definition being written
     * @return true if the lambda is cast to its raw type
     */
    boolean typedByModel(ExpressionDef value, TypeDef declaredType, Map<String, TypeDef> receiverArguments,
                                @Nullable ObjectDef objectDef) {
        if (!(value instanceof Lambda lambda) || !names.isRawGeneric(lambda.type())
            || !(TypeHierarchy.substituted(declaredType, receiverArguments) instanceof ClassTypeDef.Parameterized declared)) {
            return false;
        }
        Class<?> functional = JavaTypes.loaded(lambda.type(), generationScope.typeLookup());
        if (functional == null) {
            return false;
        }
        java.lang.reflect.Method method = functionalMethod(functional);
        if (method == null || !declared.rawType().getName().equals(functional.getName())
            || functional.getTypeParameters().length != declared.typeArguments().size()) {
            return false;
        }
        Map<String, TypeDef> arguments = new HashMap<>();
        for (int i = 0; i < functional.getTypeParameters().length; i++) {
            arguments.put(functional.getTypeParameters()[i].getName(), declared.typeArguments().get(i));
        }
        MethodDef implementation = lambda.implementation();
        TypeDef result = targetType(method.getGenericReturnType(), arguments, false);
        if (result != null && !TypeDef.VOID.equals(result)) {
            List<ExpressionDef> returned = new ArrayList<>();
            implementation.getStatements().forEach(statement -> returnedValues(statement, returned));
            if (returned.stream().anyMatch(expression -> !JavaCasts.isNullLiteral(expression)
                && conversionRules.requiresImplicitInvocationCast(result, conversionRules.sourceTypeOf(expression, implementation, objectDef)))) {
                return true;
            }
        }
        // A parameter the target types otherwise, passed where another overload takes it so
        Map<String, TypeDef> parameters = new HashMap<>();
        java.lang.reflect.Type[] genericParameters = method.getGenericParameterTypes();
        for (int i = 0; i < genericParameters.length && i < implementation.getParameters().size(); i++) {
            TypeDef target = targetType(genericParameters[i], arguments, true);
            ParameterDef parameter = implementation.getParameters().get(i);
            if (target != null && !target.equals(parameter.getType())) {
                parameters.put(parameter.getName(), target);
            }
        }
        if (parameters.isEmpty()) {
            return false;
        }
        boolean[] overloaded = {false};
        JavaLambdaRules.forEachExpression(implementation.getStatements(), expression -> {
            if (!overloaded[0]) {
                overloaded[0] = passesToAnotherOverload(expression, parameters, implementation, objectDef);
            }
        });
        return overloaded[0];
    }

    private boolean passesToAnotherOverload(ExpressionDef expression, Map<String, TypeDef> parameters,
                                                   MethodDef implementation, @Nullable ObjectDef objectDef) {
        ClassTypeDef owner;
        MethodDef method;
        List<? extends ExpressionDef> values;
        switch (expression) {
            case ExpressionDef.InvokeInstanceMethod invocation when !invocation.method().isConstructor() -> {
                owner = ownerOf(objectDef, invocation.instance().type());
                method = invocation.method();
                values = invocation.values();
            }
            case ExpressionDef.InvokeStaticMethod invocation -> {
                owner = invocation.classDef();
                method = invocation.method();
                values = invocation.values();
            }
            default -> {
                return false;
            }
        }
        boolean passed = false;
        List<@Nullable TypeDef> sourceTypes = new ArrayList<>();
        for (ExpressionDef value : values) {
            if (value instanceof VariableDef.MethodParameter parameter && parameters.containsKey(parameter.name())) {
                passed = true;
                sourceTypes.add(parameters.get(parameter.name()));
            } else {
                sourceTypes.add(JavaCasts.isNullLiteral(value) || JavaCasts.isFunctional(value) ? null
                    : conversionRules.sourceTypeOf(value, implementation, objectDef));
            }
        }
        return passed && hasApplicableOverload(owner, generationScope.definitionOf(owner, objectDef), method.getName(),
            method.getParameters().stream().map(ParameterDef::getType).toList(), sourceTypes, generationScope);
    }

    private static void returnedValues(@Nullable StatementDef statement, List<ExpressionDef> returned) {
        switch (statement) {
            case StatementDef.Return aReturn when aReturn.expression() != null -> returned.add(aReturn.expression());
            case StatementDef.Multi multi -> multi.statements().forEach(child -> returnedValues(child, returned));
            case StatementDef.If anIf -> returnedValues(anIf.statement(), returned);
            case StatementDef.IfElse ifElse -> {
                returnedValues(ifElse.statement(), returned);
                returnedValues(ifElse.elseStatement(), returned);
            }
            case StatementDef.Switch aSwitch -> {
                aSwitch.cases().values().forEach(aCase -> returnedValues(aCase, returned));
                returnedValues(aSwitch.defaultCase(), returned);
            }
            case StatementDef.While aWhile -> returnedValues(aWhile.statement(), returned);
            case StatementDef.Synchronized aSynchronized -> returnedValues(aSynchronized.statement(), returned);
            case StatementDef.Try aTry -> {
                returnedValues(aTry.statement(), returned);
                aTry.catches().forEach(aCatch -> returnedValues(aCatch.statement(), returned));
                returnedValues(aTry.finallyStatement(), returned);
            }
            case null, default -> {
            }
        }
    }

    /**
     * A type of the functional method as the target types it: a wildcard by its bound, and {@code null} where it
     * names a variable the invocation infers.
     */
    @Nullable
    private static TypeDef targetType(java.lang.reflect.Type type, Map<String, TypeDef> arguments, boolean parameter) {
        TypeDef substituted = TypeHierarchy.unwrap(TypeHierarchy.substituted(TypeHierarchy.typeDefOf(type), arguments));
        if (substituted instanceof TypeDef.Wildcard wildcard) {
            if (!wildcard.lowerBounds().isEmpty()) {
                substituted = parameter ? wildcard.lowerBounds().get(0) : TypeDef.OBJECT;
            } else {
                substituted = wildcard.upperBounds().isEmpty() ? TypeDef.OBJECT : wildcard.upperBounds().get(0);
            }
        }
        return TypeHierarchy.containsVariableOtherThan(substituted, Set.of()) ? null : substituted;
    }

    private static java.lang.reflect.@Nullable Method functionalMethod(Class<?> type) {
        java.lang.reflect.Method found = null;
        for (java.lang.reflect.Method method : type.getMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(method.getModifiers()) && !isObjectMethod(method)) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }

    private static boolean isObjectMethod(java.lang.reflect.Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * A value converted to an array of a variable of several bounds, by a generic helper of an anonymous class, whose
     * variable the invocation infers as the intersection no array type expresses.
     */
    CodeBlock renderIntersectionArray(@Nullable ObjectDef objectDef,
                                              @Nullable MethodDef methodDef,
                                              RenderScope scope,
                                              ExpressionDef value,
                                              List<TypeDef> bounds,
                                              int dimensions) {
        // Bounds can name a variable of the class or of the method, which the helper's own must not shadow
        TypeName[] boundNames = bounds.stream().map(bound -> types.asType(bound, objectDef, methodDef)).toArray(TypeName[]::new);
        String name = "T";
        for (int i = 1; names.isVariablePartOfTheDefinition(name, objectDef, methodDef, false); i++) {
            name = "T" + i;
        }
        TypeVariableName variable = TypeVariableName.get(name, boundNames);
        TypeName array = variable;
        for (int i = 0; i < dimensions; i++) {
            array = ArrayTypeName.of(array);
        }
        TypeSpec helper = TypeSpec.anonymousClassBuilder("")
            .addMethod(MethodSpec.methodBuilder("cast")
                .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "$S", "unchecked").build())
                .addTypeVariable(variable)
                .returns(array)
                .addParameter(Object.class, "value")
                .addStatement("return ($T) value", array)
                .build())
            .build();
        return CodeBlock.of("$L.cast($L)", helper, expressions.renderExpression(objectDef, methodDef, scope, value));
    }

    /**
     * The type another value passed as the same variable of the invoked method has, where that variable bounds itself.
     */
    @Nullable
    TypeDef siblingType(List<TypeDef> parameterTypes,
                                       List<? extends ExpressionDef> values,
                                       int index,
                                       List<TypeDef.TypeVariable> inferred,
                                       @Nullable MethodDef enclosingMethod,
                                       @Nullable ObjectDef objectDef) {
        if (!(TypeHierarchy.unwrap(parameterTypes.get(index)) instanceof TypeDef.TypeVariable variable)) {
            return null;
        }
        TypeDef.TypeVariable declared = inferred.stream().filter(own -> own.name().equals(variable.name())).findFirst().orElse(null);
        if (declared == null || declared.bounds().stream().noneMatch(bound -> TypeHierarchy.containsVariableOtherThan(bound, Set.of())
            && bound instanceof ClassTypeDef.Parameterized parameterized && parameterized.typeArguments().stream()
            .anyMatch(argument -> TypeHierarchy.unwrap(argument) instanceof TypeDef.TypeVariable named && named.name().equals(variable.name())))) {
            return null;
        }
        for (int i = 0; i < values.size(); i++) {
            if (i != index && TypeHierarchy.unwrap(parameterTypes.get(i)) instanceof TypeDef.TypeVariable other
                && other.name().equals(variable.name())) {
                TypeDef type = conversionRules.sourceTypeOf(values.get(i), enclosingMethod, objectDef);
                if (type instanceof ClassTypeDef && !TypeDef.OBJECT.equals(type)) {
                    return type;
                }
            }
        }
        return null;
    }

    /**
     * A numeric conversion the Java cast does not write: a primitive or a numeric or char box converted to the box
     * of another primitive, `Float.valueOf((float) l)`, or a numeric box converted to a char, `(char) i.intValue()`.
     * The value is unboxed with its own wrapper, converted as a primitive and boxed to the target's wrapper, as the
     * bytecode converts it; it throws no ClassCastException, and a null box throws a NullPointerException.
     *
     * @return The conversion, or null where the value is converted otherwise
     */
    @Nullable
    CodeBlock renderNumericConversion(@Nullable ObjectDef objectDef,
                                      @Nullable MethodDef methodDef,
                                      RenderScope scope,
                                      TypeDef target,
                                      ExpressionDef value) {
        if (isNullLiteral(value)) {
            return null;
        }
        TypeDef source = TypeHierarchy.unwrap(conversionRules.sourceTypeOf(value, methodDef, objectDef));
        TypeDef to = TypeHierarchy.unwrap(target);
        TypeDef.Primitive sourcePrimitive = source instanceof TypeDef.Primitive p ? p : JavaTypes.unboxedOf(source);
        TypeDef.Primitive targetPrimitive = to instanceof TypeDef.Primitive p ? p : JavaTypes.unboxedOf(to);
        if (sourcePrimitive == null || targetPrimitive == null || !isNumeric(sourcePrimitive) || !isNumeric(targetPrimitive)
            || sourcePrimitive.equals(targetPrimitive)) {
            return null;
        }
        if (to instanceof TypeDef.Primitive) {
            if (!targetPrimitive.equals(TypeDef.Primitive.CHAR) || source instanceof TypeDef.Primitive) {
                return null;
            }
            if (JavaLiterals.isBoxedValue(value)) {
                // A boxed constant is written as its literal: `(char) 1000`
                CodeBlock literal = expressions.renderExpression(objectDef, methodDef, scope, value);
                return CodeBlock.of("($T) $L", types.asType(targetPrimitive, objectDef, methodDef),
                    JavaPrecedence.of(value) < JavaPrecedence.POSTFIX ? expressions.addParentheses(literal) : literal);
            }
            // A number is no Character: the primitive it holds is converted to a char
            CodeBlock operand = expressions.renderCastOperand(objectDef, methodDef, scope, value);
            return CodeBlock.of("($T) $L.$LValue()", types.asType(targetPrimitive, objectDef, methodDef),
                requiresReceiverParentheses(value) ? expressions.addParentheses(operand) : operand, sourcePrimitive.name());
        }
        CodeBlock converted = expressions.renderExpression(objectDef, methodDef, scope, new ExpressionDef.Cast(targetPrimitive, value));
        return CodeBlock.of("$T.valueOf($L)", types.asType(targetPrimitive.wrapperType(), objectDef, methodDef), converted);
    }

    /**
     * Whether a primitive is a number or a char.
     */
    private static boolean isNumeric(TypeDef.Primitive primitive) {
        return !primitive.equals(TypeDef.Primitive.BOOLEAN) && !primitive.equals(TypeDef.VOID);
    }

    /**
     * A reference converted to a numeric primitive as the bytecode does it, through {@link Number}: a cast only
     * unboxes the wrapper of the primitive itself.
     */
    @Nullable
    CodeBlock renderUnboxed(@Nullable ObjectDef objectDef,
                                    @Nullable MethodDef methodDef,
                                    RenderScope scope,
                                    TypeDef target,
                                    ExpressionDef value) {
        TypeDef type = TypeHierarchy.unwrap(conversionRules.sourceTypeOf(value, methodDef, objectDef));
        if (!(TypeHierarchy.unwrap(target) instanceof TypeDef.Primitive primitive) || type instanceof TypeDef.Primitive
            || isNullLiteral(value) || primitive.equals(TypeDef.Primitive.BOOLEAN) || primitive.equals(TypeDef.Primitive.CHAR)
            || primitive.equals(JavaTypes.unboxedOf(type))) {
            return null;
        }
        if (JavaLiterals.isBoxedValue(value)) {
            // A boxed constant is written as its literal, which is converted as the box would be: `(long) 5`
            CodeBlock literal = expressions.renderExpression(objectDef, methodDef, scope, value);
            return CodeBlock.of("($T) $L", types.asType(primitive, objectDef, methodDef),
                JavaPrecedence.of(value) < JavaPrecedence.POSTFIX ? expressions.addParentheses(literal) : literal);
        }
        CodeBlock operand = expressions.renderCastOperand(objectDef, methodDef, scope, value);
        if (TypeDef.Primitive.CHAR.equals(JavaTypes.unboxedOf(type))) {
            // A `Character` is no `Number`: its `char` is converted to the numeric primitive
            return CodeBlock.of("($T) $L.charValue()", types.asType(primitive, objectDef, methodDef),
                requiresReceiverParentheses(value) ? expressions.addParentheses(operand) : operand);
        }
        return JavaTypes.unboxedOf(type) != null || TypeDef.of(Number.class).equals(type)
            ? CodeBlock.of("$L.$LValue()", requiresReceiverParentheses(value) ? expressions.addParentheses(operand) : operand, primitive.name())
            // What is no Number - a String, an array - is cast through Object, which javac accepts and which throws
            // as the bytecode's checked cast does
            : conversionRules.castsThroughObject(value, TypeDef.of(Number.class), methodDef, objectDef)
            ? CodeBlock.of("(($T) ($T) $L).$LValue()", Number.class, Object.class, operand, primitive.name())
            : CodeBlock.of("(($T) $L).$LValue()", Number.class, operand, primitive.name());
    }

    /**
     * An operand of a concatenation, in parentheses where it binds no tighter than `+` does - or, on the right, as
     * tight: `a + (b + c)` is not `a + b + c` where `a` and `b` are numbers.
     */
    CodeBlock renderConcatOperand(@Nullable ObjectDef objectDef,
                                          @Nullable MethodDef methodDef,
                                          RenderScope scope,
                                          ExpressionDef operand,
                                          boolean rightOperand) {
        CodeBlock rendered = expressions.renderExpression(objectDef, methodDef, scope, operand);
        // Decided on the node written: a cast the source drops leaves its operand, which a written cast would group
        ExpressionDef written = JavaCasts.writtenNode(operand, JavaCasts.CastContext.DEFAULT);
        return JavaPrecedence.requiresConcatOperandParentheses(written, rightOperand) ? expressions.addParentheses(rendered) : rendered;
    }
}
