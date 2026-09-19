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
import static io.micronaut.sourcegen.JavaExpressionRules.declaredSignature;
import static io.micronaut.sourcegen.JavaExpressionRules.ownerOf;
import static io.micronaut.sourcegen.JavaExpressionRules.sourceTypeOf;
import static io.micronaut.sourcegen.JavaExpressionRules.isNullLiteral;
import static io.micronaut.sourcegen.JavaExpressionRules.requiresMethodCallTargetParentheses;
import static io.micronaut.sourcegen.JavaExpressionRules.unwrapCasts;
import static io.micronaut.sourcegen.JavaOverloadRules.hasApplicableOverload;
import static io.micronaut.sourcegen.JavaOverloadRules.pinsOverload;
import static io.micronaut.sourcegen.JavaOverloadRules.receiverBound;
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
            ownerOf(objectDef, instance.type()), objectDef, methodDef, reference, JavaPoetNames.context(), false);
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
            call = CodeBlock.of("($T) $L", generator.asType(adaptation.resultType(), objectDef, methodDef), call);
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
        return adaptation.resultType() != null && JavaPoetSourceGenerator.isRawGeneric(adaptation.resultType())
            ? CodeBlock.of("($T) $L", functional, read) : read;
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
        ClassTypeDef owner = ownerOf(objectDef, instance.type());
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
     */
    CodeBlock renderIntersectionArray(@Nullable ObjectDef objectDef,
                                              @Nullable MethodDef methodDef,
                                              RenderScope scope,
                                              ExpressionDef value,
                                              List<TypeDef> bounds,
                                              int dimensions) {
        // Bounds can name a variable of the class or of the method, which the helper's own must not shadow
        TypeName[] boundNames = bounds.stream().map(bound -> generator.asType(bound, objectDef, methodDef)).toArray(TypeName[]::new);
        String name = "T";
        for (int i = 1; isVariablePartOfTheDefinition(name, objectDef, methodDef, false); i++) {
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
        return CodeBlock.of("$L.cast($L)", helper, generator.renderExpression(objectDef, methodDef, scope, value));
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
}
