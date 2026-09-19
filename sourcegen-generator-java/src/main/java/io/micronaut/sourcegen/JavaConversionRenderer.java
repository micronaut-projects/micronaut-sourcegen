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
import io.micronaut.core.reflect.ClassUtils;
import static io.micronaut.sourcegen.JavaExpressionRules.declaredSignature;
import static io.micronaut.sourcegen.JavaExpressionRules.isFunctional;
import static io.micronaut.sourcegen.JavaExpressionRules.ownerOf;
import static io.micronaut.sourcegen.JavaExpressionRules.renderPrimitiveConstant;
import static io.micronaut.sourcegen.JavaExpressionRules.sourceTypeOf;
import static io.micronaut.sourcegen.JavaExpressionRules.isNullLiteral;
import static io.micronaut.sourcegen.JavaExpressionRules.requiresMethodCallTargetParentheses;
import static io.micronaut.sourcegen.JavaExpressionRules.unwrapCasts;
import static io.micronaut.sourcegen.generator.OverloadRules.hasApplicableOverload;
import static io.micronaut.sourcegen.generator.OverloadRules.pinsOverload;
import static io.micronaut.sourcegen.generator.OverloadRules.receiverBound;
import static io.micronaut.sourcegen.JavaPoetNames.getClassName;
import static io.micronaut.sourcegen.JavaPoetNames.isVariablePartOfTheDefinition;
import org.jspecify.annotations.Nullable;
import io.micronaut.sourcegen.generator.InvokedSignature;
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
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * The conversions of the Java source generator that are written as more than a cast: a method reference as a lambda
 * that converts its arguments, an array of an intersection through a generic helper, a reference unboxed through
 * {@link Number}, and the operands of a concatenation.
 *
 * @since 2.2
 */
@Internal
final class JavaConversionRenderer {

    private final JavaPoetSourceGenerator generator;

    JavaConversionRenderer(JavaPoetSourceGenerator generator) {
        this.generator = generator;
    }

    /**
     * A reference to a generated method that override resolution narrowed, as a lambda converting its arguments and
     * result. A receiver other than `this` or a parameter, which the model never assigns, is read once and checked
     * for `null` where the reference is created, as the reference would.
     */
    @Nullable
    CodeBlock renderAdaptedReference(@Nullable ObjectDef objectDef,
                                             @Nullable MethodDef methodDef,
                                             RenderScope scope,
                                             MethodReferenceExpression reference,
                                             ExpressionDef instance) {
        OverrideResolver.ReferenceAdaptation adaptation = OverrideResolver.adaptReference(
            ownerOf(objectDef, methodDef, instance.type(), reference.method().getName(),
                reference.method().getParameters().stream().map(ParameterDef::getType).toList()),
            objectDef, methodDef, reference, JavaPoetNames.context(), false);
        if (adaptation == null) {
            adaptation = boundReference(objectDef, reference, instance);
        }
        if (adaptation == null) {
            return null;
        }
        RenderScope lambdaScope = scope.nested(null);
        // A receiver other than `this` is checked for `null` where the reference is created, as the reference would
        boolean captured = !(instance instanceof VariableDef.This || instance instanceof VariableDef.Super);
        String receiver = captured ? lambdaScope.allocate("target") : "";
        lambdaScope.declare(receiver);
        List<CodeBlock> parameters = new ArrayList<>();
        List<CodeBlock> arguments = new ArrayList<>();
        for (int i = 0; i < adaptation.argumentTypes().size(); i++) {
            String name = lambdaScope.allocate("arg");
            lambdaScope.declare(name);
            parameters.add(CodeBlock.of("$L", name));
            TypeDef type = adaptation.argumentTypes().get(i);
            CodeBlock.Builder argument = CodeBlock.builder();
            if (type != null) {
                argument.add("($T) ", generator.asType(type, objectDef, methodDef));
                adaptation.argumentConversions().get(i).forEach(conversion -> argument.add("($T) ", generator.asType(conversion, objectDef)));
            }
            arguments.add(argument.add("$L", name).build());
        }
        CodeBlock call = CodeBlock.of("$L.$L($L)", captured ? receiver
            : generator.renderExpression(objectDef, methodDef, scope, instance), reference.method().getName(),
            CodeBlock.join(arguments, ", "));
        if (adaptation.resultBound() != null) {
            // Only an unchecked conversion through the bound's raw type turns the result into the variable
            call = CodeBlock.of("($T) $L", generator.asType(adaptation.resultBound(), objectDef, methodDef), call);
        }
        if (adaptation.resultType() != null) {
            TypeDef.Primitive unboxed = unboxedResult(reference, adaptation.resultType());
            // A numeric result is unboxed through `Number`, as the call site of the reference unboxes it: a cast to the
            // wrapper of the primitive would reject a `Long` returned for an `int`
            call = unboxed != null
                ? CodeBlock.of("(($T) $L).$LValue()", Number.class, call, unboxed.name())
                : CodeBlock.of("($T) $L", generator.asType(adaptation.resultType(), objectDef, methodDef), call);
        }
        CodeBlock lambda = CodeBlock.of("($L) -> $L", CodeBlock.join(parameters, ", "), call);
        if (!captured) {
            return lambda;
        }
        TypeName functional = generator.asType(reference.type(), objectDef, methodDef);
        CodeBlock read = CodeBlock.of("$T.of($L).<$T>map($L -> $L).get()", Optional.class,
            isNullLiteral(instance) ? CodeBlock.of("($T) null", generator.asType(instance.type(), objectDef, methodDef))
                : generator.renderExpression(objectDef, methodDef, scope, instance), functional, receiver, lambda);
        // A lambda returning a raw type makes `map` an unchecked invocation, whose result is erased
        return adaptation.resultType() != null && JavaExpressionRules.isRawGeneric(adaptation.resultType())
            ? CodeBlock.of("($T) $L", functional, read) : read;
    }

    /**
     * The numeric primitive the functional interface of a reference returns, where the adapted result is its wrapper.
     */
    private static TypeDef.@Nullable Primitive unboxedResult(MethodReferenceExpression reference, TypeDef resultType) {
        TypeDef functionalReturn;
        try {
            functionalReturn = TypeHierarchy.unwrap(reference.type().getLambda().getImplementation().getReturnType());
        } catch (RuntimeException e) {
            return null;
        }
        return functionalReturn instanceof TypeDef.Primitive primitive && primitive.isNumber()
            && primitive.wrapperType().equals(TypeHierarchy.unwrap(resultType)) ? primitive : null;
    }

    /**
     * A static or constructor reference to an overloaded method, as a lambda casting the values the functional
     * interface passes to the parameters of the model: `String::valueOf` names the overload javac finds most
     * specific for the interface, where the model names another.
     */
    @Nullable
    CodeBlock renderPinnedReference(@Nullable ObjectDef objectDef,
                                    @Nullable MethodDef methodDef,
                                    RenderScope scope,
                                    MethodReferenceExpression reference) {
        MethodDef method = reference.method();
        ClassTypeDef owner = reference.owner();
        List<TypeDef> parameterTypes = method.getParameters().stream().map(ParameterDef::getType).toList();
        List<TypeDef> passed;
        try {
            MethodDef functional = reference.type().getLambda().getImplementation();
            if (!functional.getTypeVariables().isEmpty()) {
                // A generic functional method has no lambda
                return null;
            }
            passed = functional.getParameters().stream().map(ParameterDef::getType).toList();
        } catch (RuntimeException e) {
            // A functional interface known only by name has no members to read
            return null;
        }
        String name = reference.isConstructor() ? MethodDef.CONSTRUCTOR : method.getName();
        if (passed.size() != parameterTypes.size()
            || !hasApplicableOverload(owner, OverrideResolver.definitionOf(owner, objectDef), name, parameterTypes, new ArrayList<>(passed))) {
            return null;
        }
        RenderScope lambdaScope = scope.nested(null);
        List<CodeBlock> parameters = new ArrayList<>();
        List<CodeBlock> arguments = new ArrayList<>();
        for (int i = 0; i < parameterTypes.size(); i++) {
            String argument = lambdaScope.allocate("arg");
            lambdaScope.declare(argument);
            parameters.add(CodeBlock.of("$L", argument));
            TypeDef parameterType = parameterTypes.get(i);
            arguments.add(pinsOverload(parameterType, passed.get(i), method.getTypeVariables())
                ? CodeBlock.of("($T) $L", generator.asType(parameterType instanceof ClassTypeDef.Parameterized parameterized
                    ? parameterized.rawType() : parameterType, objectDef, methodDef), argument)
                : CodeBlock.of("$L", argument));
        }
        CodeBlock call = reference.isConstructor()
            ? CodeBlock.of("new $T($L)", generator.asType(owner, objectDef, methodDef), CodeBlock.join(arguments, ", "))
            : CodeBlock.of("$T.$L($L)", generator.asType(owner, objectDef, methodDef), method.getName(), CodeBlock.join(arguments, ", "));
        return CodeBlock.of("($L) -> $L", CodeBlock.join(parameters, ", "), call);
    }

    /**
     * The casts a reference is written with, as a lambda, so that it names the method of the model: where the owner
     * declares another overload the functional interface would select, and where the receiver binds a parameter -
     * {@code apply} of a {@code Function<String, String>} takes no {@code Object}.
     */
    OverrideResolver.@Nullable ReferenceAdaptation boundReference(@Nullable ObjectDef objectDef,
                                                                          MethodReferenceExpression reference,
                                                                          ExpressionDef instance) {
        MethodDef method = reference.method();
        List<TypeDef> parameterTypes = method.getParameters().stream().map(ParameterDef::getType).toList();
        ClassTypeDef owner = ownerOf(objectDef, null, instance.type(), method.getName(), parameterTypes);
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
        boolean overloaded = hasApplicableOverload(owner, OverrideResolver.definitionOf(owner, objectDef), method.getName(),
            parameterTypes, new ArrayList<>(passed));
        InvokedSignature signature = declaredSignature(owner, method.getName(), parameterTypes);
        Map<String, TypeDef> receiverArguments = new HashMap<>(OverrideResolver.receiverArguments(owner, objectDef, method));
        List<@Nullable TypeDef> argumentTypes = new ArrayList<>();
        List<List<TypeDef>> conversions = new ArrayList<>();
        boolean converted = false;
        for (int i = 0; i < parameterTypes.size(); i++) {
            TypeDef bound = receiverBound(parameterTypes.get(i),
                signature != null && signature.parameterTypes().size() == parameterTypes.size() ? signature.parameterTypes().get(i) : null,
                method.getTypeVariables(), receiverArguments);
            boolean cast = overloaded && pinsOverload(bound, passed.get(i), method.getTypeVariables())
                || !bound.equals(parameterTypes.get(i)) && JavaExpressionRules.requiresImplicitInvocationCast(bound, passed.get(i));
            argumentTypes.add(cast ? bound instanceof ClassTypeDef.Parameterized parameterized ? parameterized.rawType() : bound : null);
            conversions.add(List.of());
            converted |= cast;
        }
        return converted ? new OverrideResolver.ReferenceAdaptation(argumentTypes, conversions, null, null) : null;
    }

    /**
     * A value converted to an array of a variable of several bounds, by a generic helper of an anonymous class, whose
     * variable the invocation infers as the intersection no array type expresses.
     *
     * <p>A bound naming the callee's variable itself - `Comparable<N>` of an `N extends Number & Comparable<N>` -
     * javac infers no helper variable against: the whole invocation is written in a helper declaring the variable,
     * which the value is cast to an array of. Where the method being written is that helper, the cast is written;
     * otherwise the invocation is escaped from with a {@link SelfBoundedArgument}.
     *
     * @param calleeVariable The name of the callee's variable the bounds are of
     */
    CodeBlock renderIntersectionArray(@Nullable ObjectDef objectDef,
                                              @Nullable MethodDef methodDef,
                                              RenderScope scope,
                                              ExpressionDef value,
                                              List<TypeDef> bounds,
                                              int dimensions,
                                              String calleeVariable) {
        TypeDef.TypeVariable declared = declaredIntersection(methodDef, bounds, calleeVariable);
        if (declared != null) {
            return CodeBlock.of("($T) $L", generator.asType(TypeDef.array(TypeDef.variable(declared.name()), dimensions), objectDef, methodDef),
                generator.renderCastOperand(objectDef, methodDef, scope, value));
        }
        if (bounds.stream().anyMatch(bound -> names(bound, calleeVariable))) {
            throw new SelfBoundedArgument(bounds, calleeVariable);
        }
        // Bounds can name a variable of the class or of the method, which the helper's own must not shadow
        String name = helperVariableName(objectDef, methodDef);
        TypeName[] boundNames = bounds.stream().map(bound -> generator.asType(bound, objectDef, methodDef)).toArray(TypeName[]::new);
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
        return CodeBlock.of("$L.cast($L)", helper, generator.renderExpression(objectDef, methodDef, scope, value));
    }

    /**
     * An invocation with an argument converted to an array of a variable bounded by itself, written in a generic
     * helper method of an anonymous class that declares the variable: `first((T[]) values)` infers the callee's
     * `N extends Number & Comparable<N>` as a declared `T extends Number & Comparable<T>`, where no inferred
     * variable satisfies the bound. The receiver and the values are passed to the helper, in their order.
     *
     * @param invocation The invocation: a static or instance method call, or an instantiation
     * @param argument   The argument that escaped the invocation
     * @return The invocation through the helper, or {@code null} where it cannot be written in one
     */
    @Nullable
    CodeBlock renderSelfBoundedInvocation(@Nullable ObjectDef objectDef,
                                          @Nullable MethodDef methodDef,
                                          RenderScope scope,
                                          ExpressionDef invocation,
                                          SelfBoundedArgument argument) {
        if (declaredIntersection(methodDef, argument.bounds, argument.calleeVariable) != null) {
            // The helper itself: its variable was not found where the argument is written
            return null;
        }
        ExpressionDef receiver = invocation instanceof ExpressionDef.InvokeInstanceMethod instanceMethod ? instanceMethod.instance() : null;
        List<? extends ExpressionDef> values = switch (invocation) {
            case ExpressionDef.InvokeStaticMethod staticMethod -> staticMethod.values();
            case ExpressionDef.InvokeInstanceMethod instanceMethod when !instanceMethod.method().isConstructor()
                && !(instanceMethod.instance() instanceof VariableDef.Super) && !isFunctional(instanceMethod.instance()) -> instanceMethod.values();
            case ExpressionDef.NewInstance newInstance -> newInstance.values();
            default -> null;
        };
        if (values == null) {
            return null;
        }
        String name = helperVariableName(objectDef, methodDef);
        TypeDef.TypeVariable own = TypeDef.variable(name);
        Map<String, TypeDef> asOwn = Map.of(argument.calleeVariable, own);
        TypeDef.TypeVariable declared = TypeDef.variable(name,
            argument.bounds.stream().map(bound -> TypeHierarchy.substituted(bound, asOwn)).toList());
        MethodDef helperMethod = JavaPoetNames.withTypeVariables(MethodDef.builder("invoke").addTypeVariable(declared).build(), methodDef);
        RenderScope helperScope = scope.nested(null);
        MethodSpec.Builder helper = MethodSpec.methodBuilder("invoke")
            .addAnnotation(AnnotationSpec.builder(SuppressWarnings.class).addMember("value", "$S", "unchecked").build())
            .addTypeVariable(TypeVariableName.get(name, declared.bounds().stream()
                .map(bound -> generator.asType(bound, objectDef, helperMethod)).toArray(TypeName[]::new)))
            .returns(generator.asType(invocation.type(), objectDef, helperMethod));
        List<CodeBlock> passed = new ArrayList<>();
        VariableDef.Local target = null;
        if (receiver != null) {
            target = new VariableDef.Local(helperScope.allocate("target"), receiver.type());
            helperScope.declare(target.name());
            helper.addParameter(generator.asType(receiver.type(), objectDef, methodDef), target.name());
            passed.add(generator.renderExpression(objectDef, methodDef, scope, receiver));
        }
        List<VariableDef.Local> locals = new ArrayList<>();
        for (ExpressionDef value : values) {
            VariableDef.Local local = new VariableDef.Local(helperScope.allocate("arg"), value.type());
            helperScope.declare(local.name());
            locals.add(local);
            helper.addParameter(generator.asType(value.type(), objectDef, methodDef), local.name());
            passed.add(generator.renderExpression(objectDef, methodDef, scope, value));
        }
        ExpressionDef inner = switch (invocation) {
            case ExpressionDef.InvokeStaticMethod staticMethod -> new ExpressionDef.InvokeStaticMethod(staticMethod.classDef(), staticMethod.method(), locals);
            case ExpressionDef.InvokeInstanceMethod instanceMethod ->
                new ExpressionDef.InvokeInstanceMethod(Objects.requireNonNull(target), instanceMethod.method(), instanceMethod.isDefault(), locals);
            case ExpressionDef.NewInstance newInstance -> new ExpressionDef.NewInstance(newInstance.type(), newInstance.parameterTypes(), locals);
            default -> throw new IllegalStateException("Unexpected invocation: " + invocation);
        };
        CodeBlock call = generator.renderExpression(objectDef, helperMethod, helperScope, inner);
        helper.addStatement(TypeDef.VOID.equals(invocation.type()) ? CodeBlock.of("$L", call) : CodeBlock.of("return $L", call));
        return CodeBlock.of("$L.invoke($L)", TypeSpec.anonymousClassBuilder("").addMethod(helper.build()).build(),
            CodeBlock.join(passed, ", "));
    }

    /**
     * The name of the variable a helper declares, which must not shadow one of the class or of the method.
     */
    private static String helperVariableName(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        String name = "T";
        for (int i = 1; isVariablePartOfTheDefinition(name, objectDef, methodDef, false); i++) {
            name = "T" + i;
        }
        return name;
    }

    /**
     * The variable the method being written declares with the bounds of the callee's variable, where it is the helper
     * of a self-bounded invocation, or {@code null}.
     */
    private static TypeDef.@Nullable TypeVariable declaredIntersection(@Nullable MethodDef methodDef,
                                                                     List<TypeDef> bounds,
                                                                     String calleeVariable) {
        if (methodDef == null) {
            return null;
        }
        for (TypeDef.TypeVariable variable : methodDef.getTypeVariables()) {
            Map<String, TypeDef> asOwn = Map.of(calleeVariable, TypeDef.variable(variable.name()));
            if (variable.bounds().equals(bounds.stream().map(bound -> TypeHierarchy.substituted(bound, asOwn)).toList())) {
                return variable;
            }
        }
        return null;
    }

    private static boolean names(TypeDef type, String variable) {
        return !TypeHierarchy.substituted(type, Map.of(variable, TypeDef.OBJECT)).equals(type);
    }

    /**
     * A value that is not an array, passed for a varargs parameter: one element, or - an `Object`, or a value of
     * no element type - the array itself, cast to as the bytecode casts it.
     */
    CodeBlock renderVarargsValue(@Nullable ObjectDef objectDef,
                                 @Nullable MethodDef enclosingMethod,
                                 RenderScope scope,
                                 TypeDef paramType,
                                 List<TypeDef.TypeVariable> inferred,
                                 ExpressionDef value) {
        TypeDef.Array varargsType = (TypeDef.Array) TypeHierarchy.unwrap(paramType);
        TypeDef elementType = varargsType.dimensions() == 1 ? varargsType.componentType()
            : TypeDef.array(varargsType.componentType(), varargsType.dimensions() - 1);
        TypeDef sourceType = sourceTypeOf(value, enclosingMethod, objectDef);
        if (TypeHierarchy.unwrap(sourceType) instanceof TypeDef.Array) {
            // A value that is not an array is one element of the varargs. Where an override narrowed it to an
            // array, it is cast to the element type, which keeps it one
            return CodeBlock.concat(CodeBlock.of("($T) ", generator.asType(elementType, objectDef, enclosingMethod)),
                generator.renderCastOperand(objectDef, enclosingMethod, scope, value));
        }
        if (JavaExpressionRules.isVarargsArray(elementType, sourceType)) {
            // The bytecode casts the value to the array type, which the source does too - of the erasure of a
            // variable of the method
            TypeDef arrayType = Objects.requireNonNullElse(
                JavaExpressionRules.erasedPinningType(paramType, inferred, objectDef, enclosingMethod), paramType);
            return CodeBlock.concat(CodeBlock.of("($T) ", generator.asType(arrayType, objectDef, enclosingMethod)),
                generator.renderCastOperand(objectDef, enclosingMethod, scope, value));
        }
        return generator.renderExpression(objectDef, enclosingMethod, scope, value);
    }

    /**
     * The final copies of the locals the lambdas of a statement capture where the enclosing body assigns them
     * after declaring them, written before the statement: the bytecode captures the value the local has where the
     * lambda is created, and Java captures only an effectively final local. The lambdas read the copies.
     */
    CodeBlock renderCapturedCopies(@Nullable ObjectDef objectDef,
                                   @Nullable MethodDef methodDef,
                                   RenderScope scope,
                                   StatementDef statementDef) {
        return renderCapturedCopies(objectDef, methodDef, scope,
            JavaSourceRules.capturedReassignedLocals(statementDef, scope::isReassigned));
    }

    /**
     * The final copies of the locals the lambdas of the header expression of a compound statement capture - the
     * condition of an `if`, say - written before the statement.
     */
    CodeBlock renderCapturedCopies(@Nullable ObjectDef objectDef,
                                   @Nullable MethodDef methodDef,
                                   RenderScope scope,
                                   ExpressionDef header) {
        return renderCapturedCopies(objectDef, methodDef, scope,
            JavaSourceRules.capturedReassignedLocals(header, scope::isReassigned));
    }

    private CodeBlock renderCapturedCopies(@Nullable ObjectDef objectDef,
                                           @Nullable MethodDef methodDef,
                                           RenderScope scope,
                                           Map<String, TypeDef> captured) {
        if (captured.isEmpty()) {
            return CodeBlock.of("");
        }
        CodeBlock.Builder builder = CodeBlock.builder();
        captured.forEach((name, type) -> {
            String emittedName = Objects.requireNonNullElse(scope.resolveRename(name), name);
            String copyName = scope.allocate(emittedName);
            scope.copy(name, copyName);
            builder.addStatement("final $T $L = $L", generator.asType(type, objectDef, methodDef), copyName, emittedName);
        });
        return builder.build();
    }

    /**
     * A constant: a literal, an enum constant, a class literal or an array of constants.
     */
    CodeBlock renderConstant(RenderScope scope, ExpressionDef.Constant constant) {
        TypeDef type = constant.type();
        Object value = constant.value();
        if (value == null) {
            return CodeBlock.of("null");
        }
        return switch (type) {
            case ClassTypeDef classTypeDef when classTypeDef.isEnum() -> generator.renderExpression(
                null,
                null,
                scope,
                classTypeDef.getStaticField(value instanceof Enum<?> anEnum ? anEnum.name() : value.toString(), type)
            );
            case TypeDef.Primitive primitive -> renderPrimitiveConstant(primitive.name(), value);
            case TypeDef.Array arrayDef -> {
                if (value.getClass().isArray()) {
                    final var array = value;
                    final var values = IntStream.range(0, Array.getLength(array))
                        .mapToObj(i -> renderConstant(scope, new ExpressionDef.Constant(arrayDef.componentType(), Array.get(array, i))))
                        .collect(CodeBlock.joining(", "));
                    final String typeName;
                    if (arrayDef.componentType() instanceof ClassTypeDef arrayClassTypeDef) {
                        typeName = arrayClassTypeDef.getSimpleName();
                    } else if (arrayDef.componentType() instanceof TypeDef.Primitive arrayPrimitive) {
                        typeName = arrayPrimitive.name();
                    } else {
                        throw new IllegalStateException("Unrecognized expression: " + constant);
                    }
                    yield CodeBlock.concat(
                        CodeBlock.of("new $N[] {", typeName),
                        values,
                        CodeBlock.of("}"));
                }
                throw new IllegalStateException("Expected an array; got: " + value.getClass());
            }
            case ClassTypeDef classTypeDef -> {
                String name = classTypeDef.getName();
                if (ClassUtils.isJavaLangType(name)) {
                    yield switch (name) {
                        case "java.lang.String" -> CodeBlock.of("$S", value);
                        // A boxed value is written as the literal of its primitive, which boxes to the same type
                        case "java.lang.Long" -> renderPrimitiveConstant("long", value);
                        case "java.lang.Float" -> renderPrimitiveConstant("float", value);
                        case "java.lang.Double" -> renderPrimitiveConstant("double", value);
                        case "java.lang.Character" -> renderPrimitiveConstant("char", value);
                        case "java.lang.Byte" -> renderPrimitiveConstant("byte", value);
                        case "java.lang.Short" -> renderPrimitiveConstant("short", value);
                        default -> CodeBlock.of("$L", value);
                    };
                }
                if (value instanceof TypeDef typeDef) {
                    yield CodeBlock.of("$L.class", getClassName(typeDef));
                }
                if (value instanceof Class<?> aClass) {
                    yield CodeBlock.of("$T.class", aClass);
                }
                yield CodeBlock.of("$L", value);
            }
            default -> throw new IllegalStateException("Unrecognized expression: " + constant);
        };
    }

    /**
     * The type another value passed as the same variable of the invoked method has, where that variable bounds itself.
     */
    @Nullable
    static TypeDef siblingType(List<TypeDef> parameterTypes,
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
                TypeDef type = sourceTypeOf(values.get(i), enclosingMethod, objectDef);
                if (type instanceof ClassTypeDef && !TypeDef.OBJECT.equals(type)) {
                    return type;
                }
            }
        }
        return null;
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
        TypeDef type = TypeHierarchy.unwrap(sourceTypeOf(value, methodDef, objectDef));
        if (!(TypeHierarchy.unwrap(target) instanceof TypeDef.Primitive primitive) || type instanceof TypeDef.Primitive
            || isNullLiteral(value) || primitive.equals(TypeDef.Primitive.BOOLEAN) || primitive.equals(TypeDef.Primitive.CHAR)
            || primitive.equals(JavaExpressionRules.unboxedOf(type))) {
            return null;
        }
        CodeBlock operand = generator.renderCastOperand(objectDef, methodDef, scope, value);
        return JavaExpressionRules.unboxedOf(type) != null || TypeDef.of(Number.class).equals(type)
            ? CodeBlock.of("$L.$LValue()", requiresMethodCallTargetParentheses(value) ? generator.addParentheses(operand) : operand, primitive.name())
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
        CodeBlock rendered = generator.renderExpression(objectDef, methodDef, scope, operand);
        ExpressionDef unwrapped = unwrapCasts(operand);
        boolean cast = operand instanceof ExpressionDef.Cast;
        boolean grouped = !cast && (unwrapped instanceof ExpressionDef.IfElse
            || unwrapped instanceof ExpressionDef.ConditionExpressionDef
            || unwrapped instanceof ExpressionDef.Switch
            || unwrapped instanceof Lambda
            || unwrapped instanceof ExpressionDef.MathBinaryOperation
            || rightOperand && unwrapped instanceof ExpressionDef.StringConcatenation);
        return grouped ? generator.addParentheses(rendered) : rendered;
    }

    /**
     * Escapes an invocation whose argument converts to an array of a variable of the invoked method bounded by
     * itself, which is written through a helper declaring the variable.
     */
    static final class SelfBoundedArgument extends IllegalStateException {

        private final transient List<TypeDef> bounds;
        private final String calleeVariable;

        SelfBoundedArgument(List<TypeDef> bounds, String calleeVariable) {
            super("An array of the variable " + calleeVariable + " bounded by " + bounds + " cannot be written outside of a helper");
            this.bounds = bounds;
            this.calleeVariable = calleeVariable;
        }
    }
}
