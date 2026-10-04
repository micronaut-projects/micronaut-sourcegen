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
import io.micronaut.sourcegen.generator.InvokedSignature;
import io.micronaut.sourcegen.generator.OverloadRules;
import io.micronaut.sourcegen.generator.OverrideResolver;
import io.micronaut.sourcegen.javapoet.CodeBlock;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.JavaIdioms;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.TypeOperations;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import static io.micronaut.sourcegen.JavaCasts.CastContext;
import static io.micronaut.sourcegen.JavaCasts.arePrimitiveReferenceEqualityOperands;
import static io.micronaut.sourcegen.JavaCasts.collapseNestedCasts;
import static io.micronaut.sourcegen.JavaCasts.dropsCast;
import static io.micronaut.sourcegen.JavaCasts.hasFunctionalBranch;
import static io.micronaut.sourcegen.JavaCasts.isFunctional;
import static io.micronaut.sourcegen.JavaCasts.isNullLiteral;
import static io.micronaut.sourcegen.JavaCasts.unwrapCasts;
import static io.micronaut.sourcegen.JavaCasts.writtenNode;
import static io.micronaut.sourcegen.JavaPoetNames.withTypeVariables;
import static io.micronaut.sourcegen.JavaPrecedence.operator;
import static io.micronaut.sourcegen.JavaPrecedence.requiresCastOperandParentheses;
import static io.micronaut.sourcegen.JavaPrecedence.requiresMathParentheses;
import static io.micronaut.sourcegen.JavaPrecedence.requiresParentheses;
import static io.micronaut.sourcegen.JavaPrecedence.requiresReceiverParentheses;
import static io.micronaut.sourcegen.JavaSourceRules.singleExpressionBody;
import static io.micronaut.sourcegen.JavaTypes.ownerOf;
import static io.micronaut.sourcegen.JavaTypes.sameErasure;
import static io.micronaut.sourcegen.generator.OverloadRules.hasApplicableOverload;
import static io.micronaut.sourcegen.generator.OverloadRules.pinsOverload;
import static io.micronaut.sourcegen.generator.OverloadRules.receiverBound;

/**
 * The expressions of a file the Java source generator writes, converted as the bytecode converts them where Java
 * needs a cast the bytecode does not. One is created for each file, with the context of writing it.
 *
 * @since 2.3
 */
@Internal
final class JavaExpressionRenderer {

    private final JavaTypeRenderer types;
    private final JavaWriteContext context;
    private final JavaConversionRules conversionRules;
    private final JavaConversionRenderer conversions;
    private final JavaStatementRenderer statementRenderer;
    private final JavaLiterals literals;

    JavaExpressionRenderer(JavaTypeRenderer types, JavaWriteContext context) {
        this.types = types;
        this.context = context;
        this.conversionRules = context.conversions();
        this.conversions = new JavaConversionRenderer(this);
        this.statementRenderer = new JavaStatementRenderer(this);
        this.literals = new JavaLiterals(this);
    }

    JavaTypeRenderer types() {
        return types;
    }

    JavaWriteContext context() {
        return context;
    }

    JavaStatementRenderer statementRenderer() {
        return statementRenderer;
    }

    JavaLiterals literals() {
        return literals;
    }

    CodeBlock renderExpression(@Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       RenderScope scope,
                                       ExpressionDef expressionDef) {
        return renderExpression(objectDef, methodDef, scope, expressionDef, CastContext.DEFAULT);
    }

    CodeBlock renderExpression(@Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       RenderScope scope,
                                       ExpressionDef expressionDef,
                                       boolean isRef) {
        return renderExpression(objectDef, methodDef, scope, expressionDef, isRef ? CastContext.OBJECT_REFERENCE : CastContext.DEFAULT);
    }

    CodeBlock renderExpression(@Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       RenderScope scope,
                                       ExpressionDef expressionDef,
                                       CastContext castContext) {
        switch (expressionDef) {
            case ExpressionDef.ConditionExpressionDef conditionExpressionDef -> {
                return renderCondition(objectDef, methodDef, scope, conditionExpressionDef, false);
            }
            case ExpressionDef.NewInstance newInstance -> {
                return CodeBlock.concat(
                    CodeBlock.of("new $L(", renderInstantiated(newInstance.type(), objectDef, methodDef)),
                    renderInvocationArguments(objectDef, methodDef, scope, newInstance.type(), MethodDef.CONSTRUCTOR,
                        newInstance.parameterTypes(), List.of(),
                        OverrideResolver.receiverArguments(newInstance.type(), objectDef,
                            MethodDef.constructor().addParameters(newInstance.parameterTypes()).build(), context.scope()),
                        newInstance.values()),
                    CodeBlock.of(")")
                );
            }
            case ExpressionDef.ArrayElement arrayElement -> {
                return renderArrayElement(objectDef, methodDef, scope, arrayElement);
            }
            case ExpressionDef.NewArrayOfSize newArray -> {
                // The size belongs to the first dimension: `new T[size][]`
                return CodeBlock.of("new $T[$L]$L", types.asType(JavaTypes.creationComponent(newArray.type(), objectDef, methodDef),
                        objectDef, methodDef), newArray.size(),
                    "[]".repeat(newArray.type().dimensions() - 1));
            }
            case ExpressionDef.NewArrayInitialized newArray -> {
                return renderNewArrayInitialized(objectDef, methodDef, scope, newArray);
            }
            case ExpressionDef.Cast castExpressionDef -> {
                ExpressionDef exp = collapseNestedCasts(castExpressionDef.expressionDef());
                if (dropsCast(castExpressionDef, exp, castContext)) {
                    return renderExpression(objectDef, methodDef, scope, exp, castContext);
                }
                CodeBlock unboxed = conversions.renderUnboxed(objectDef, methodDef, scope, castExpressionDef.type(), exp);
                if (unboxed != null) {
                    return unboxed;
                }
                CodeBlock numeric = conversions.renderNumericConversion(objectDef, methodDef, scope, castExpressionDef.type(), exp);
                if (numeric != null) {
                    return numeric;
                }
                TypeDef castType = castExpressionDef.type();
                if (castType instanceof ClassTypeDef.Parameterized parameterized
                    && conversionRules.sourceTypeOf(exp, methodDef, objectDef) instanceof ClassTypeDef.Parameterized narrowed
                    && !narrowed.equals(exp.type()) && conversionRules.requiresRawCastTo(castType, narrowed, objectDef, methodDef)) {
                    // A value an override narrowed to a parameterization the cast does not accept is cast raw
                    castType = parameterized.rawType();
                }
                CodeBlock explicitCast = CodeBlock.of("($T)", types.asType(castType, objectDef, methodDef));
                CodeBlock rendered = renderExpression(objectDef, methodDef, scope, exp);
                if (conversionRules.castsThroughObject(exp, castType, methodDef, objectDef)) {
                    return CodeBlock.of("$L ($T) $L", explicitCast, Object.class, renderCastOperand(objectDef, methodDef, scope, exp));
                }
                if (isFunctional(exp) && !sameErasure(castType, exp.type())) {
                    rendered = withTargetType(objectDef, methodDef, exp, rendered);
                }
                ExpressionDef castOperand = unwrapCasts(exp);
                if (!requiresCastOperandParentheses(castOperand)) {
                    return CodeBlock.concat(explicitCast, CodeBlock.of(" "), rendered);
                }
                return CodeBlock.concat(
                    explicitCast,
                    CodeBlock.of(" "),
                    addParentheses(rendered)
                );
            }
            case ExpressionDef.Constant constant -> {
                return literals.render(objectDef, methodDef, constant);
            }
            case ExpressionDef.InvokeInstanceMethod invokeInstanceMethod -> {
                return renderInvokeInstanceMethod(objectDef, methodDef, scope, invokeInstanceMethod);
            }
            case ExpressionDef.InvokeStaticMethod staticMethod -> {
                return CodeBlock.concat(
                    // A static member is named by the raw type: `List<String>.of(value)` does not compile
                    CodeBlock.of("$T.$L(", types.asType(TypeOperations.rawClass(staticMethod.classDef()), objectDef), staticMethod.method().getName()),
                    renderInvocationArguments(objectDef, methodDef, scope, staticMethod.classDef(),
                        staticMethod.method(), staticMethod.values()),
                    CodeBlock.of(")")
                );
            }
            case ExpressionDef.GetPropertyValue getPropertyValue -> {
                return renderExpression(objectDef, methodDef, scope, JavaIdioms.getPropertyValue(getPropertyValue));
            }
            case ExpressionDef.MathBinaryOperation mathOperation -> {
                return narrowed(objectDef, mathOperation, CodeBlock.concat(
                    renderMathOperand(objectDef, methodDef, scope, mathOperation, mathOperation.left(), false),
                    CodeBlock.of(operator(mathOperation)),
                    renderMathOperand(objectDef, methodDef, scope, mathOperation, mathOperation.right(), true)
                ));
            }
            case ExpressionDef.MathUnaryOperation mathOperation -> {
                CodeBlock operand = renderExpressionWithParentheses(objectDef, methodDef, scope, mathOperation.expression());
                if (!requiresParentheses(mathOperation.expression())
                    && JavaPrecedence.startsWithMinus(writtenNode(mathOperation.expression(), CastContext.DEFAULT))) {
                    // `-(-1)`: `--1` is a decrement
                    operand = addParentheses(operand);
                }
                return narrowed(objectDef, mathOperation, CodeBlock.concat(CodeBlock.of(operator(mathOperation)), operand));
            }
            case ExpressionDef.IfElse condition -> {
                return renderIfElse(objectDef, methodDef, scope, condition, false);
            }
            case ExpressionDef.Switch aSwitch -> {
                return renderSwitch(objectDef, methodDef, scope, aSwitch, false);
            }
            case ExpressionDef.SwitchYieldCase switchYieldCase -> {
                return statementRenderer.renderSwitchYieldCase(objectDef, methodDef, scope, switchYieldCase, false);
            }
            case VariableDef variableDef -> {
                return renderVariable(objectDef, methodDef, scope, variableDef);
            }
            case ExpressionDef.InvokeGetClassMethod invokeGetClassMethod -> {
                return renderExpression(objectDef, methodDef, scope, JavaIdioms.getClass(invokeGetClassMethod));
            }
            case ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod -> {
                return renderExpression(objectDef, methodDef, scope, JavaIdioms.hashCode(invokeHashCodeMethod));
            }
            case Lambda lambda -> {
                return renderLambda(objectDef, methodDef, scope, lambda);
            }
            case MethodReferenceExpression methodReference -> {
                return renderMethodReference(objectDef, methodDef, scope, methodReference);
            }
            case ExpressionDef.StringConcatenation concat -> {
                return renderStringConcatenation(objectDef, methodDef, scope, concat);
            }
            case null, default -> throw new IllegalStateException("Unrecognized expression: " + expressionDef);
        }
    }

    private CodeBlock renderArrayElement(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, ExpressionDef.ArrayElement arrayElement) {
        CodeBlock array = renderReceiver(objectDef, methodDef, scope, arrayElement.expression());
        if (JavaPrecedence.isArrayCreation(writtenNode(arrayElement.expression(), CastContext.DEFAULT))) {
            // `(new Object[2])[1]`: `new Object[2][1]` creates an array of two dimensions
            array = addParentheses(array);
        }
        return CodeBlock.concat(
            array,
            CodeBlock.of("["),
            renderExpression(objectDef, methodDef, scope, arrayElement.indexExpression()),
            CodeBlock.of("]")
        );
    }

    private CodeBlock renderNewArrayInitialized(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, ExpressionDef.NewArrayInitialized newArray) {
        CodeBlock.Builder builder = CodeBlock.builder();
        builder.add("new $T{", types.asType(TypeDef.array(JavaTypes.creationComponent(newArray.type(), objectDef, methodDef),
            newArray.type().dimensions()), objectDef, methodDef));
        for (Iterator<? extends ExpressionDef> iterator = newArray.nestedExpressionsStream().iterator(); iterator.hasNext(); ) {
            ExpressionDef expression = iterator.next();
            builder.add(renderStored(objectDef, methodDef, scope, newArray.type().dimensions() == 1
                ? newArray.type().componentType()
                : TypeDef.array(newArray.type().componentType(), newArray.type().dimensions() - 1), expression));
            if (iterator.hasNext()) {
                builder.add(",");
            }
        }
        builder.add("}");
        return builder.build();
    }

    private CodeBlock renderInvokeInstanceMethod(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, ExpressionDef.InvokeInstanceMethod invokeInstanceMethod) {
        MethodDef callMethod = invokeInstanceMethod.method();
        CodeBlock instance;
        if (callMethod.isConstructor() && invokeInstanceMethod.instance() instanceof VariableDef.Super) {
            // A super constructor call is always the unqualified `super(...)`, even when the
            // `VariableDef.Super` carries an explicit type used only to resolve the constructor
            // overload for bytecode generation; `Type.super(...)` is not valid Java syntax here.
            instance = CodeBlock.of("super");
        } else {
            if (isFunctional(invokeInstanceMethod.instance())) {
                // A lambda has no type of its own to call a method of
                instance = addParentheses(withTargetType(objectDef, methodDef, invokeInstanceMethod.instance(),
                    renderExpression(objectDef, methodDef, scope, invokeInstanceMethod.instance())));
            } else if (callMethod.isConstructor()) {
                instance = renderExpression(objectDef, methodDef, scope, invokeInstanceMethod.instance());
            } else {
                instance = renderReceiver(objectDef, methodDef, scope, invokeInstanceMethod.instance());
            }
        }
        // The name is a `$L` argument: a generated method name can contain a `$`, read as a placeholder
        CodeBlock methodNameAndOpenParen = callMethod.isConstructor()
            ? CodeBlock.of("(")
            : CodeBlock.of(".$L(", callMethod.getName());
        return CodeBlock.concat(
            instance,
            methodNameAndOpenParen,
            renderInvocationArguments(objectDef, methodDef, scope,
                ownerOf(objectDef, invokeInstanceMethod.instance().type()),
                callMethod, invokeInstanceMethod.values()),
            CodeBlock.of(")")
        );
    }

    /**
     * @param targeted Whether a functional result is cast to its type: the context of the expression gives it none
     */
    private CodeBlock renderIfElse(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope,
                                   ExpressionDef.IfElse condition, boolean targeted) {
        CodeBlock conditionBlock = renderExpression(objectDef, methodDef, scope, condition.condition());
        if (unwrapCasts(condition.condition()) instanceof ExpressionDef.IfElse) {
            // `?:` is right-associative, a conditional used as a condition needs parentheses
            conditionBlock = addParentheses(conditionBlock);
        }
        boolean boxed = conversionRules.promotesResults(condition, methodDef, objectDef);
        return CodeBlock.concat(
            conditionBlock,
            CodeBlock.of(" ? "),
            renderBranch(objectDef, methodDef, scope, condition.type(), condition.ifExpression(), targeted, boxed),
            CodeBlock.of(" : "),
            renderBranch(objectDef, methodDef, scope, condition.type(), condition.elseExpression(), targeted, boxed)
        );
    }

    private CodeBlock renderSwitch(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope,
                                   ExpressionDef.Switch aSwitch, boolean targeted) {
        CodeBlock.Builder builder = CodeBlock.builder();
        builder.add("switch (");
        builder.add(renderExpression(objectDef, methodDef, scope, aSwitch.expression()));
        builder.add(") {\n");
        builder.indent();
        boolean boxed = conversionRules.promotesResults(aSwitch, methodDef, objectDef);
        for (Map.Entry<ExpressionDef.Constant, ? extends ExpressionDef> e : aSwitch.cases().entrySet()) {
            builder.add("case ");
            builder.add(literals.render(objectDef, methodDef, e.getKey()));
            builder.add(" -> ");
            builder.add(renderSwitchResult(objectDef, methodDef, scope, aSwitch, e.getValue(), targeted, boxed));
        }
        if (aSwitch.defaultCase() != null) {
            builder.add("default");
            builder.add(" -> ");
            builder.add(renderSwitchResult(objectDef, methodDef, scope, aSwitch, aSwitch.defaultCase(), targeted, boxed));
        }
        builder.unindent();
        builder.add("}");
        return builder.build();
    }

    private CodeBlock renderSwitchResult(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope,
                                         ExpressionDef.Switch aSwitch, ExpressionDef value, boolean targeted, boolean boxed) {
        if (value instanceof ExpressionDef.SwitchYieldCase block) {
            return CodeBlock.concat(statementRenderer.renderSwitchYieldCase(objectDef, methodDef, scope, block, boxed), CodeBlock.of("\n"));
        }
        return CodeBlock.concat(renderBranch(objectDef, methodDef, scope, aSwitch.type(), value, targeted, boxed), CodeBlock.of(";\n"));
    }

    private CodeBlock renderLambda(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, Lambda lambda) {
        // The variables the enclosing method declares are in scope of the body
        MethodDef implementation = withTypeVariables(lambda.implementation(), methodDef);
        // Java forbids a lambda parameter from shadowing a name that is already in scope, so a
        // colliding parameter is emitted under an allocated name and its references remapped
        RenderScope lambdaScope = scope.nested(implementation);
        // A renamed parameter does not take the name of a local the body declares, which the body reserves
        lambdaScope.body(implementation.getStatements()).handling(context.exceptions().functionalThrows(lambda.type()));
        // The locals it captures which the enclosing body assigns are read from their copies, and those it assigns
        // itself from copies of its own, which the bytecode passes it
        scope.copiesOf(lambda).forEach(lambdaScope::renameLocal);
        CodeBlock.Builder prologue = CodeBlock.builder();
        Set<String> assigned = JavaLambdaRules.assignedLocals(implementation.getStatements());
        JavaLambdaRules.capturedLocals(lambda).forEach((name, type) -> {
            if (assigned.contains(name)) {
                String captured = lambdaScope.resolveLocal(name);
                String copy = lambdaScope.declareCopy(name);
                prologue.addStatement("$T $L = $L", types.asType(type, objectDef, implementation), copy, captured);
            }
        });
        CodeBlock.Builder builder = CodeBlock.builder();
        builder.add("(");
        Iterator<ParameterDef> parameter = implementation.getParameters().iterator();
        while (parameter.hasNext()) {
            String emittedName = lambdaScope.declareLambdaParameter(parameter.next().getName());
            // A `$` in the name is no placeholder
            builder.add("$L", emittedName);
            if (parameter.hasNext()) {
                builder.add(", ");
            }
        }
        builder.add(") -> ");
        List<StatementDef> statements = implementation.getStatements();
        ExpressionDef body = singleExpressionBody(lambda);
        if (body != null && !context.sourceRules().hasBlockBody(lambda, objectDef)) {
            builder.add(renderConverted(objectDef, implementation, lambdaScope, body,
                conversionRules.returnCasts(implementation.getReturnType(), body.type(),
                    conversionRules.sourceTypeOf(body, implementation, objectDef), objectDef, implementation)));
        } else {
            builder.add("{\n").indent();
            builder.add(prologue.build());
            builder.add(statementRenderer.renderBody(objectDef, implementation, lambdaScope, statements));
            builder.unindent().add("}");
        }
        return builder.build();
    }

    private CodeBlock renderMethodReference(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, MethodReferenceExpression methodReference) {
        // The name is a `$L` argument: a generated method name can contain a `$`, read as a placeholder
        String name = methodReference.isConstructor() ? "new" : methodReference.method().getName();
        ExpressionDef instance = methodReference.instance();
        CodeBlock adapted = methodReference.isConstructor() && instance != null ? null
            : conversions.renderAdaptedReference(objectDef, methodDef, scope, methodReference, instance);
        if (adapted != null) {
            return adapted;
        }
        if (instance == null) {
            // A static member is named by the raw type, `Optional<String>::ofNullable` does not compile
            return CodeBlock.of("$T::$L", types.asType(methodReference.isConstructor() ? methodReference.owner()
                : TypeOperations.rawClass(methodReference.owner()), objectDef), name);
        }
        return CodeBlock.concat(renderReceiver(objectDef, methodDef, scope, instance), CodeBlock.of("::$L", name));
    }

    private CodeBlock renderStringConcatenation(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, ExpressionDef.StringConcatenation concat) {
        ExpressionDef left = concat.left();
        ExpressionDef right = concat.right();
        CodeBlock leftBlock = conversions.renderConcatOperand(objectDef, methodDef, scope, left, false);
        if (!left.type().equals(TypeDef.STRING) && !right.type().equals(TypeDef.STRING)) {
            // `valueOf(Object)`, which writes what the concatenation does - `valueOf(char[])` writes the contents
            leftBlock = left.type().isPrimitive()
                ? CodeBlock.of("$T.valueOf($L)", String.class, renderExpression(objectDef, methodDef, scope, left))
                : CodeBlock.of("$T.valueOf(($T) $L)", String.class, Object.class, renderCastOperand(objectDef, methodDef, scope, left));
        } else if (isNullLiteral(left) && !right.type().equals(TypeDef.STRING)) {
            // A cast of `null` is not written, which leaves no String to concatenate: `null + 1`
            leftBlock = CodeBlock.of("($T) null", String.class);
        }
        CodeBlock rightBlock = isNullLiteral(right) && !left.type().equals(TypeDef.STRING) ? CodeBlock.of("($T) null", String.class)
            : conversions.renderConcatOperand(objectDef, methodDef, scope, right, true);
        return CodeBlock.concat(leftBlock, CodeBlock.of(" + "), rightBlock);
    }

    /**
     * An arithmetic operation of the type the model gives it: {@code (short) (a - b)} of shorts, which Java
     * promotes to an int (see {@link JavaTypes#isNarrowedOperation}).
     */
    private CodeBlock narrowed(@Nullable ObjectDef objectDef, ExpressionDef operation, CodeBlock rendered) {
        TypeDef.Primitive primitive = JavaTypes.narrowedPrimitive(operation);
        if (primitive == null) {
            return rendered;
        }
        return CodeBlock.of("($T) ($L)", types.asType(primitive, objectDef), rendered);
    }

    private CodeBlock renderMathOperand(@Nullable ObjectDef objectDef,
                                        @Nullable MethodDef methodDef,
                                        RenderScope scope,
                                        ExpressionDef.MathBinaryOperation parent,
                                        ExpressionDef operand,
                                        boolean rightOperand) {
        if (operand instanceof ExpressionDef.MathBinaryOperation child) {
            CodeBlock rendered = renderExpression(objectDef, methodDef, scope, child);
            if (requiresMathParentheses(parent, child, rightOperand)) {
                return addParentheses(rendered);
            }
            return rendered;
        }
        return renderExpressionWithParentheses(objectDef, methodDef, scope, operand);
    }

    private CodeBlock renderExpressionWithParentheses(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, ExpressionDef expressionDef) {
        return renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef, CastContext.DEFAULT);
    }

    private CodeBlock renderExpressionWithParentheses(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, ExpressionDef expressionDef, boolean isRef) {
        return renderExpressionWithParentheses(objectDef, methodDef, scope, expressionDef, isRef ? CastContext.OBJECT_REFERENCE : CastContext.DEFAULT);
    }

    private CodeBlock renderExpressionWithParentheses(@Nullable ObjectDef objectDef,
                                                      @Nullable MethodDef methodDef,
                                                      RenderScope scope,
                                                      ExpressionDef expressionDef,
                                                      CastContext castContext) {
        var rendered = renderExpression(objectDef, methodDef, scope, expressionDef, castContext);
        if (!requiresParentheses(expressionDef)) {
            return rendered;
        }
        return addParentheses(rendered);
    }

    /**
     * Renders a method or constructor call's argument list, inserting an explicit cast wherever the
     * declared parameter type is narrower than the argument's own static type.
     *
     * <p>Generated dispatch code routinely funnels every argument through a single {@code Object}
     * parameter (e.g. reflection-free property or method dispatch); {@code ByteCodeWriter} tolerates
     * passing such a value directly, since the JVM verifier only requires the invoked method's actual
     * parameter slots to be populated correctly, but javac needs an explicit cast down to the real
     * parameter type or the call does not type-check.
     *
     * @param objectDef      The object definition
     * @param enclosingMethod The method definition
     * @param scope          The render scope
     * @param callMethod     The method or constructor being invoked
     * @param values         The argument values
     * @return The rendered, comma-separated argument list
     */
    CodeBlock renderInvocationArguments(@Nullable ObjectDef objectDef,
                                              @Nullable MethodDef enclosingMethod,
                                              RenderScope scope,
                                              @Nullable ClassTypeDef owner,
                                              MethodDef callMethod,
                                              List<? extends ExpressionDef> values) {
        List<ParameterDef> parameters = callMethod.getParameters();
        List<TypeDef> parameterTypes = parameters.size() == values.size()
            ? parameters.stream().map(ParameterDef::getType).toList()
            : null;
        if (parameterTypes != null) {
            // A generated method that override resolution narrowed - of this class or another - is written with the
            // narrowed parameters, as the receiver sees them, which the values passed to it are converted to
            OverrideResolver.OverriddenMethod emitted =
                OverrideResolver.emittedSignature(owner, objectDef, enclosingMethod, callMethod, context.scope(), false);
            if (emitted != null) {
                parameterTypes = emitted.parameterTypes();
            }
        }
        // The receiver's type arguments bind the class variables the bounds of the invoked method's variables name -
        // not the variables the method declares itself, which shadow the class's of the same name
        Map<String, TypeDef> receiverArguments = new HashMap<>(OverrideResolver.receiverArguments(owner, objectDef, callMethod, context.scope()));
        if (receiverArguments.isEmpty() && owner != null && !(owner instanceof ClassTypeDef.Parameterized)
            && context.scope().definitionOf(owner, objectDef) == null) {
            // A raw receiver of a compiled class erases the variables of the class, which share only their names
            // with the ones in scope where the method is called
            receiverArguments.putAll(OverloadRules.erasedClassVariables(owner));
        }
        callMethod.getTypeVariables().forEach(variable -> receiverArguments.remove(variable.name()));
        return renderInvocationArguments(objectDef, enclosingMethod, scope, owner, callMethod.getName(),
            parameterTypes, callMethod.getTypeVariables(), receiverArguments, values);
    }

    /**
     * A value written with the casts it needs, the outer first, which a cast in the model would not keep: a
     * cast to the raw bound of a variable is not one to the type the value has.
     */
    CodeBlock renderConverted(@Nullable ObjectDef objectDef,
                                    @Nullable MethodDef methodDef,
                                    RenderScope scope,
                                    ExpressionDef value,
                                    List<List<TypeDef>> casts) {
        if (casts.isEmpty()) {
            return renderExpression(objectDef, methodDef, scope, value);
        }
        return renderConverted(objectDef, methodDef, scope, value, casts, false);
    }

    private CodeBlock renderConverted(@Nullable ObjectDef objectDef,
                                      @Nullable MethodDef methodDef,
                                      RenderScope scope,
                                      ExpressionDef value,
                                      List<List<TypeDef>> casts,
                                      boolean writtenOut) {
        if (casts.size() == 1 && casts.get(0).size() == 1) {
            CodeBlock unboxed = conversions.renderUnboxed(objectDef, methodDef, scope, casts.get(0).get(0), value);
            if (unboxed != null) {
                return unboxed;
            }
        }
        if (!writtenOut && casts.size() == 1 && casts.get(0).size() == 1) {
            return renderExpression(objectDef, methodDef, scope, value.cast(casts.get(0).get(0)));
        }
        // An intersection of bounds is cast to as `(Number & Runnable)`
        return CodeBlock.concat(casts.stream().map(cast -> CodeBlock.of("($L) ", cast.stream()
            .map(type -> CodeBlock.of("$T", types.asType(type, objectDef, methodDef))).collect(CodeBlock.joining(" & "))))
            .collect(CodeBlock.joining("")), renderCastOperand(objectDef, methodDef, scope, value));
    }

    /**
     * A result of a conditional or a switch expression, converted to the type the model gives the expression where
     * the result is of a supertype of it. A narrower result is left alone, so the expression keeps the source type.
     */
    private CodeBlock renderBranch(@Nullable ObjectDef objectDef,
                                   @Nullable MethodDef methodDef,
                                   RenderScope scope,
                                   TypeDef type,
                                   ExpressionDef value,
                                   boolean targeted,
                                   boolean boxed) {
        if (targeted && isFunctional(value)) {
            // Java types a conditional from an assignment or invocation context only: a cast of the whole gives the
            // lambdas in it no type, each is cast
            return withTargetType(objectDef, methodDef, value, renderExpression(objectDef, methodDef, scope, value));
        }
        if (targeted && value instanceof ExpressionDef.IfElse || targeted && value instanceof ExpressionDef.Switch) {
            return renderTargetedBranches(objectDef, methodDef, scope, value);
        }
        if (boxed) {
            // Of a reference type, where Java would promote the results as numbers: each is converted to the type
            return renderExpression(objectDef, methodDef, scope, value.cast(type));
        }
        if (type instanceof TypeDef.Primitive primitive && value.type() instanceof TypeDef.Primitive valueType
            && !primitive.equals(valueType) && !TypeDef.VOID.equals(valueType)) {
            // Java promotes the results of a conditional to a common type: each is converted to the one of the model,
            // `flag ? 1 : (long) 2.5` is a long
            return renderExpression(objectDef, methodDef, scope, value.cast(type));
        }
        if (value instanceof ExpressionDef.SwitchYieldCase || isNullLiteral(value)) {
            return renderExpression(objectDef, methodDef, scope, value);
        }
        if (type instanceof TypeDef.Primitive primitive && !value.type().isPrimitive()) {
            // A reference result is converted to the primitive of the model as the bytecode converts it, where Java
            // would unbox it and promote it with the other: through Number, or checked as the box of a char or a boolean
            return renderUnboxedBranch(objectDef, methodDef, scope, primitive, value);
        }
        if (conversionRules.castsThroughObject(value, type, methodDef, objectDef)
            || conversions.renderNumericConversion(objectDef, methodDef, scope, type, value) != null) {
            // A result javac cannot convert to the type, `flag ? 1 : 2` of a String, is checked as the bytecode checks it
            return renderExpression(objectDef, methodDef, scope, value.cast(type));
        }
        if (type.isPrimitive() || value.type().isPrimitive() || !conversionRules.requiresImplicitInvocationCast(type, value.type())) {
            return renderExpression(objectDef, methodDef, scope, value);
        }
        return renderExpression(objectDef, methodDef, scope, value.cast(type));
    }

    private CodeBlock renderUnboxedBranch(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope,
                                          TypeDef.Primitive type, ExpressionDef value) {
        TypeDef.Primitive unboxed = JavaTypes.unboxedOf(TypeHierarchy.unwrap(conversionRules.sourceTypeOf(value, methodDef, objectDef)));
        if (type.equals(unboxed)) {
            return renderExpression(objectDef, methodDef, scope, value);
        }
        return renderExpression(objectDef, methodDef, scope, value.cast(type));
    }

    /**
     * A conditional or a switch whose functional results are cast to their type, where the context gives it none.
     */
    CodeBlock renderTargetedBranches(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope,
                                           ExpressionDef value) {
        return value instanceof ExpressionDef.IfElse condition ? renderIfElse(objectDef, methodDef, scope, condition, true)
            : renderSwitch(objectDef, methodDef, scope, (ExpressionDef.Switch) value, true);
    }

    /**
     * A value stored where the model declares a type - a local, a field, an array element, the result of a
     * conditional - converted to it as the bytecode converts every value it stores.
     */
    CodeBlock renderStored(@Nullable ObjectDef objectDef,
                           @Nullable MethodDef methodDef,
                           RenderScope scope,
                           TypeDef targetType,
                           ExpressionDef value) {
        return renderToTarget(objectDef, methodDef, scope, targetType, value, Target.STORED);
    }

    /**
     * A value converted to the type it is stored or returned as: through the raw type where the target would fix a
     * variable of the generic method it calls that an argument does not convert to, a lambda cast to its type where
     * the target gives it another, and anything else with the casts a returned value needs.
     *
     * @param objectDef  The definition being written
     * @param methodDef  The method being written
     * @param scope      The scope
     * @param targetType The type of the target
     * @param value      The value
     * @param target     What the target is
     * @return The value
     */
    CodeBlock renderToTarget(@Nullable ObjectDef objectDef,
                             @Nullable MethodDef methodDef,
                             RenderScope scope,
                             TypeDef targetType,
                             ExpressionDef value,
                             Target target) {
        TypeDef raw = context.generics().conflictingResult(value, targetType, objectDef, methodDef);
        if (raw != null) {
            // The target would fix a variable of the generic method an argument does not convert to
            return CodeBlock.of("($T) $L", types.asType(raw, objectDef, methodDef), renderCastOperand(objectDef, methodDef, scope, value));
        }
        if (target == Target.STORED
            && (isNullLiteral(value) || TypeDef.VOID.equals(targetType) || value.type().equals(TypeDef.VOID))) {
            return renderExpression(objectDef, methodDef, scope, value);
        }
        boolean retyped = !sameErasure(targetType, value.type());
        if (isFunctional(value) && (retyped || target == Target.STORED)) {
            CodeBlock rendered = renderExpression(objectDef, methodDef, scope, value);
            return retyped ? withTargetType(objectDef, methodDef, value, rendered) : rendered;
        }
        if (hasFunctionalBranch(value) && retyped) {
            return renderTargetedBranches(objectDef, methodDef, scope, value);
        }
        TypeDef sourceType = conversionRules.sourceTypeOf(value, methodDef, objectDef);
        // A stored value is converted as the source types it, a returned one as the model does
        TypeDef valueType = target == Target.RETURNED || sourceType.equals(value.type()) ? value.type() : sourceType;
        return renderConverted(objectDef, methodDef, scope, value,
            conversionRules.returnCasts(targetType, valueType, sourceType, objectDef, methodDef));
    }

    private CodeBlock renderInvocationArguments(@Nullable ObjectDef objectDef,
                                                @Nullable MethodDef enclosingMethod,
                                                RenderScope scope,
                                                @Nullable ClassTypeDef owner,
                                                @Nullable String methodName,
                                                @Nullable List<TypeDef> parameterTypes,
                                                List<TypeDef.TypeVariable> inferred,
                                                Map<String, TypeDef> receiverArguments,
                                                List<? extends ExpressionDef> values) {
        List<TypeDef> sameArityParameterTypes = parameterTypes != null && parameterTypes.size() == values.size()
            ? parameterTypes : null;
        // The signature the invoked method declares, which carries the type arguments the erased model does not
        InvokedSignature signature = methodName == null || sameArityParameterTypes == null ? null
            : conversionRules.declaredSignature(owner, methodName, sameArityParameterTypes);
        // A generated method, which cannot be looked up, declares the types it is written with, and the variables
        // of its class are fixed by the receiver
        boolean generated = signature == null && context.scope().definitionOf(owner, objectDef) != null;
        List<TypeDef> declaredTypes = signature != null ? signature.parameterTypes()
            : generated ? sameArityParameterTypes : null;
        // Only a method whose signature says so takes varargs: an unresolved one - such as a generated method - is
        // taken as declared, with its array parameter an array
        boolean varargs = signature != null && signature.varargs();
        boolean overloaded = methodName != null && sameArityParameterTypes != null
            && hasApplicableOverload(owner, context.scope().definitionOf(owner, objectDef), methodName, sameArityParameterTypes,
            values.stream().map(value -> isNullLiteral(value) || isFunctional(value) ? null : conversionRules.sourceTypeOf(value, enclosingMethod, objectDef)).toList(),
            context.scope());
        Map<String, List<TypeDef>> methodVariables = JavaSignatures.methodVariables(owner, methodName, sameArityParameterTypes, inferred);
        return IntStream.range(0, values.size())
            .mapToObj(i -> {
                ExpressionDef value = values.get(i);
                if (sameArityParameterTypes != null) {
                    TypeDef paramType = receiverBound(sameArityParameterTypes.get(i),
                        declaredTypes != null && declaredTypes.size() == values.size() ? declaredTypes.get(i) : null,
                        inferred, receiverArguments);
                    boolean vararg = varargs && i == values.size() - 1
                        && TypeHierarchy.unwrap(paramType) instanceof TypeDef.Array
                        && !(TypeHierarchy.unwrap(value.type()) instanceof TypeDef.Array);
                    if (vararg) {
                        // A value that is not an array is one element of the varargs, which a cast would not be.
                        // Where an override narrowed it to an array, it is cast to the element type, which keeps it one
                        if (TypeHierarchy.unwrap(conversionRules.sourceTypeOf(value, enclosingMethod, objectDef)) instanceof TypeDef.Array
                            && TypeHierarchy.unwrap(paramType) instanceof TypeDef.Array varargsType) {
                            TypeDef elementType = varargsType.dimensions() == 1 ? varargsType.componentType()
                                : TypeDef.array(varargsType.componentType(), varargsType.dimensions() - 1);
                            return CodeBlock.concat(
                                CodeBlock.of("($T) ", types.asType(elementType, objectDef, enclosingMethod)),
                                renderCastOperand(objectDef, enclosingMethod, scope, value)
                            );
                        }
                        return renderExpression(objectDef, enclosingMethod, scope, value);
                    }
                    if (isFunctional(value)) {
                        // A lambda takes its type from the parameter: an overloaded method, or one taking an `Object`,
                        // gives it none. One of a raw type the model gives it is typed by it, where the parameter would
                        // type its parameters and result by the type arguments of the receiver
                        CodeBlock rendered = renderExpression(objectDef, enclosingMethod, scope, value);
                        boolean raw = declaredTypes != null && declaredTypes.size() == values.size()
                            && conversions.typedByModel(value, declaredTypes.get(i), receiverArguments, objectDef);
                        return overloaded || raw || !sameErasure(paramType, value.type())
                            ? withTargetType(objectDef, enclosingMethod, value, rendered) : rendered;
                    }
                    TypeDef sourceType = conversionRules.sourceTypeOf(value, enclosingMethod, objectDef);
                    List<TypeDef> intersection = conversionRules.intersectionArrayBounds(paramType, sourceType, inferred, receiverArguments,
                        objectDef, enclosingMethod);
                    if (intersection != null) {
                        return conversions.renderIntersectionArray(objectDef, enclosingMethod, scope, value, intersection,
                            ((TypeDef.Array) TypeHierarchy.unwrap(paramType)).dimensions());
                    }
                    TypeDef sibling = TypeDef.OBJECT.equals(sourceType) ? conversions.siblingType(sameArityParameterTypes, values, i, inferred,
                        enclosingMethod, objectDef) : null;
                    if (sibling != null) {
                        // `max(text, (String) value)` of `<T extends Comparable<T>>`: the bound alone infers no `T`
                        return CodeBlock.concat(CodeBlock.of("($T) ", types.asType(sibling, objectDef, enclosingMethod)),
                            renderCastOperand(objectDef, enclosingMethod, scope, value));
                    }
                    TypeDef declared = declaredTypes != null && declaredTypes.size() == values.size() ? declaredTypes.get(i) : null;
                    List<List<TypeDef>> casts = conversionRules.argumentCasts(paramType, value.type(), sourceType, declared,
                        generated, inferred, receiverArguments, objectDef, enclosingMethod);
                    if (casts.isEmpty() && declared != null
                        && conversionRules.passesWildcardForBoundedVariable(declared, sourceType, methodVariables)) {
                        // Only an unchecked invocation passes a capture where a bounded variable is inferred
                        casts = List.of(List.of(TypeOperations.raw(paramType)));
                    }
                    if (casts.size() < 2 && !sourceType.equals(value.type()) && !paramType.equals(sourceType)
                        && (!casts.isEmpty() || !(sourceType instanceof TypeDef.Primitive))) {
                        if (!casts.isEmpty()) {
                            // Written out: the cast can be to the type the value has in the model
                            return renderConverted(objectDef, enclosingMethod, scope, value, casts, true);
                        }
                        // An override narrowed the parameter the value names - `Object value` to `String value` -
                        // which would select another overload than the one the model calls: keep its type. Written
                        // out, since in the model the cast is to the type the value already has, which is dropped. A
                        // parameterized type is cast to as raw: the narrowed one need not relate to it
                        return CodeBlock.concat(CodeBlock.of("($T) ", types.asType(paramType instanceof ClassTypeDef.Parameterized
                            parameterized ? parameterized.rawType() : paramType, objectDef, enclosingMethod)),
                            renderCastOperand(objectDef, enclosingMethod, scope, value)
                        );
                    }
                    if (casts.isEmpty() && overloaded && !isNullLiteral(value)
                        && TypeHierarchy.unwrap(paramType) instanceof TypeDef.TypeVariable variable && methodVariables.containsKey(variable.name())) {
                        // A variable of the invoked method takes the value as the source types it, where a more specific
                        // overload takes it too: its erasure names the method of the model
                        TypeDef erasure = JavaTypes.erasure(TypeDef.variable(variable.name(),
                            methodVariables.get(variable.name())), objectDef, enclosingMethod);
                        if (!TypeHierarchy.erasedName(erasure).equals(TypeHierarchy.erasedName(sourceType))) {
                            return CodeBlock.concat(CodeBlock.of("($T) ", types.asType(erasure, objectDef, enclosingMethod)),
                                renderCastOperand(objectDef, enclosingMethod, scope, value));
                        }
                    }
                    if (casts.isEmpty() && overloaded && pinsOverload(paramType, isNullLiteral(value) ? null
                        : OverloadRules.lexicalErasure(sourceType, objectDef, enclosingMethod), inferred)) {
                        // Another overload takes the values as the source types them: the cast names the one of the model
                        return CodeBlock.concat(CodeBlock.of("($T) ", types.asType(paramType instanceof ClassTypeDef.Parameterized
                            parameterized ? parameterized.rawType() : paramType, objectDef, enclosingMethod)),
                            isNullLiteral(value) ? CodeBlock.of("null") : renderCastOperand(objectDef, enclosingMethod, scope, value));
                    }
                    return renderConverted(objectDef, enclosingMethod, scope, value, casts);
                }
                return renderExpression(objectDef, enclosingMethod, scope, value);
            })
            .collect(CodeBlock.joining(", "));
    }

    CodeBlock withTargetType(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, ExpressionDef functional, CodeBlock rendered) {
        return CodeBlock.of("($T) $L", types.asType(functional.type(), objectDef, methodDef), rendered);
    }

    CodeBlock renderCastOperand(@Nullable ObjectDef objectDef,
                                        @Nullable MethodDef methodDef,
                                        RenderScope scope,
                                        ExpressionDef value) {
        CodeBlock rendered = renderExpression(objectDef, methodDef, scope, value);
        return requiresCastOperandParentheses(unwrapCasts(value)) ? addParentheses(rendered) : rendered;
    }

    /**
     * The receiver of a call, a field or an array access, which a primary is: an expression that binds less tightly
     * is put in parentheses, and a boxed constant is written as the box the bytecode pushes.
     */
    CodeBlock renderReceiver(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, ExpressionDef instance) {
        ExpressionDef written = writtenNode(instance, CastContext.DEFAULT);
        if (written instanceof ExpressionDef.Constant constant) {
            CodeBlock boxed = literals.renderBoxed(objectDef, methodDef, constant);
            if (boxed != null) {
                return boxed;
            }
        }
        TypeDef raw = context.generics().uninferredReceiver(written, objectDef);
        if (raw != null) {
            // A variable no argument names is inferred from its bounds alone, which the model does not bind
            return addParentheses(CodeBlock.of("($T) $L", types.asType(raw, objectDef, methodDef), renderCastOperand(objectDef, methodDef, scope, instance)));
        }
        CodeBlock rendered = renderExpression(objectDef, methodDef, scope, instance);
        return requiresReceiverParentheses(instance) || JavaPrecedence.of(written) < JavaPrecedence.POSTFIX
            ? addParentheses(rendered) : rendered;
    }

    /**
     * The type an instance is created of, written qualified: a wildcard cannot be instantiated, `new ArrayList<?>()` is
     * written with the diamond, which infers what the bytecode creates, the erasure.
     */
    CodeBlock renderInstantiated(ClassTypeDef type, @Nullable ObjectDef objectDef, @Nullable MethodDef methodDef) {
        if (TypeHierarchy.unwrap(type) instanceof ClassTypeDef.Parameterized parameterized
            && parameterized.typeArguments().stream().anyMatch(argument -> TypeHierarchy.unwrap(argument) instanceof TypeDef.Wildcard)) {
            return CodeBlock.of("$L<>", types.asType(parameterized.rawType(), objectDef, methodDef));
        }
        return CodeBlock.of("$L", types.asType(type, objectDef, methodDef));
    }

    CodeBlock addParentheses(CodeBlock rendered) {
        return CodeBlock.concat(
            CodeBlock.of("("),
            rendered,
            CodeBlock.of(")")
        );
    }

    private CodeBlock renderCondition(@Nullable ObjectDef objectDef,
                                      @Nullable MethodDef methodDef,
                                      RenderScope scope,
                                      ExpressionDef.ConditionExpressionDef expressionDef,
                                      boolean isRef) {
        switch (expressionDef) {
            case ExpressionDef.IsNull isNull -> {
                return renderCondition(objectDef, methodDef, scope, new ExpressionDef.ComparisonOperation(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, isNull.expression(), ExpressionDef.nullValue()), true);
            }
            case ExpressionDef.IsNotNull isNotNull -> {
                return renderCondition(objectDef, methodDef, scope, new ExpressionDef.ComparisonOperation(ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO, isNotNull.expression(), ExpressionDef.nullValue()), true);
            }
            case ExpressionDef.IsTrue isTrue -> {
                ExpressionDef expression = unwrapCasts(isTrue.expression());
                if (expression instanceof ExpressionDef.ConditionExpressionDef conditionExpressionDef) {
                    return renderCondition(objectDef, methodDef, scope, conditionExpressionDef, isRef);
                }
                return renderExpressionWithParentheses(objectDef, methodDef, scope, isTrue.expression());
            }
            case ExpressionDef.IsFalse isFalse -> {
                ExpressionDef expression = unwrapCasts(isFalse.expression());
                if (expression instanceof ExpressionDef.ConditionExpressionDef conditionExpressionDef) {
                    return CodeBlock.concat(
                        CodeBlock.of("!"),
                        addParentheses(renderCondition(objectDef, methodDef, scope, conditionExpressionDef, isRef))
                    );
                }
                return CodeBlock.concat(
                    CodeBlock.of("!"),
                    renderExpressionWithParentheses(objectDef, methodDef, scope, isFalse.expression())
                );
            }
            case ExpressionDef.ComparisonOperation comparisonOperation -> {
                if (comparisonOperation.opType() == ExpressionDef.ComparisonOperation.OpType.EQUAL_TO
                    || comparisonOperation.opType() == ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO) {
                    return renderReferenceComparison(objectDef, methodDef, scope, comparisonOperation.left(),
                        comparisonOperation.right(), operator(comparisonOperation), isRef);
                }
                return CodeBlock.concat(
                    renderExpressionWithParentheses(objectDef, methodDef, scope, comparisonOperation.left(), isRef),
                    CodeBlock.of(operator(comparisonOperation)),
                    renderExpressionWithParentheses(objectDef, methodDef, scope, comparisonOperation.right(), isRef)
                );
            }
            case ExpressionDef.InstanceOf instanceOf -> {
                // A cast to `Object` is not written, which leaves the operand it wraps
                ExpressionDef operand = writtenNode(instanceOf.expression(), CastContext.OBJECT_REFERENCE);
                CodeBlock rendered = renderExpression(objectDef, methodDef, scope, instanceOf.expression(), true);
                return CodeBlock.concat(
                    conversionRules.castsThroughObject(operand, instanceOf.instanceType(), methodDef, objectDef)
                        ? CodeBlock.of("(($T) $L)", Object.class, renderCastOperand(objectDef, methodDef, scope, operand))
                        : JavaPrecedence.of(operand) <= JavaPrecedence.RELATIONAL ? addParentheses(rendered) : rendered,
                    CodeBlock.of(" instanceof "),
                    // Fully qualified like before, but passed as an argument so a `$` in the name is not a placeholder
                    CodeBlock.of("$L", context.names().asClassType(instanceOf.instanceType()).canonicalName())
                );
            }
            case ExpressionDef.And andExpressionDef -> {
                return CodeBlock.concat(
                    renderAndConditionOperand(objectDef, methodDef, scope, andExpressionDef.left()),
                    CodeBlock.of(" && "),
                    renderAndConditionOperand(objectDef, methodDef, scope, andExpressionDef.right())
                );
            }
            case ExpressionDef.Or orExpressionDef -> {
                return CodeBlock.concat(
                    renderCondition(objectDef, methodDef, scope, orExpressionDef.left(), false),
                    CodeBlock.of(" || "),
                    renderCondition(objectDef, methodDef, scope, orExpressionDef.right(), false)
                );
            }
            case ExpressionDef.EqualsStructurally equalsStructurally -> {
                List<ExpressionDef> operands = JavaCasts.structuralOperands(equalsStructurally.instance(), equalsStructurally.other());
                if (operands != null) {
                    return renderReferenceComparison(objectDef, methodDef, scope, operands.get(0), operands.get(1), " == ", null);
                }
                return renderExpressionWithParentheses(objectDef, methodDef, scope, JavaIdioms.equalsStructurally(equalsStructurally));
            }
            case ExpressionDef.NotEqualsStructurally notEqualsStructurally -> {
                List<ExpressionDef> operands = JavaCasts.structuralOperands(notEqualsStructurally.instance(), notEqualsStructurally.other());
                if (operands != null) {
                    return renderReferenceComparison(objectDef, methodDef, scope, operands.get(0), operands.get(1), " != ", null);
                }
                return renderExpressionWithParentheses(objectDef, methodDef, scope, JavaIdioms.equalsStructurally(notEqualsStructurally.instance(), notEqualsStructurally.other()).isFalse());
            }
            case ExpressionDef.EqualsReferentially equalsReferentially -> {
                return renderReferenceComparison(objectDef, methodDef, scope, equalsReferentially.instance(),
                    equalsReferentially.other(), " == ", null);
            }
            case ExpressionDef.NotEqualsReferentially notEqualsReferentially -> {
                return renderReferenceComparison(objectDef, methodDef, scope, notEqualsReferentially.instance(),
                    notEqualsReferentially.other(), " != ", null);
            }
            case null, default -> throw new IllegalStateException("Unrecognized condition: " + expressionDef);
        }
    }

    private CodeBlock renderAndConditionOperand(@Nullable ObjectDef objectDef,
                                                @Nullable MethodDef methodDef,
                                                RenderScope scope,
                                                ExpressionDef.ConditionExpressionDef expressionDef) {
        CodeBlock rendered = renderCondition(objectDef, methodDef, scope, expressionDef, false);
        // An `||` binds less tightly than `&&`
        if (JavaPrecedence.of(expressionDef) < JavaPrecedence.AND) {
            return addParentheses(rendered);
        }
        return rendered;
    }

    private CodeBlock renderReferenceComparison(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, ExpressionDef left, ExpressionDef right, String operator, @Nullable Boolean isRef) {
        CastContext castContext = isRef != null ? (isRef ? CastContext.OBJECT_REFERENCE : CastContext.DEFAULT)
            : arePrimitiveReferenceEqualityOperands(left, right) ? CastContext.PRIMITIVE_EQUALITY : CastContext.OBJECT_REFERENCE;
        // An operand an override narrowed to a type the other is not comparable with is compared as an `Object`
        // - a cast to `Object` is not written in a comparison of references, so the operands are what it wraps
        ExpressionDef leftOperand = left instanceof ExpressionDef.Cast cast && TypeDef.OBJECT.equals(cast.type()) ? cast.expressionDef() : left;
        ExpressionDef rightOperand = right instanceof ExpressionDef.Cast cast && TypeDef.OBJECT.equals(cast.type()) ? cast.expressionDef() : right;
        boolean widened = conversionRules.comparesThroughObject(leftOperand, rightOperand, methodDef, objectDef)
            || conversionRules.comparesThroughObject(rightOperand, leftOperand, methodDef, objectDef);
        return CodeBlock.builder()
            .add(widened ? CodeBlock.of("($T) ", Object.class) : CodeBlock.of(""))
            .add(renderComparedOperand(objectDef, methodDef, scope, left, right, castContext))
            .add(operator)
            .add(renderComparedOperand(objectDef, methodDef, scope, right, left, castContext))
            .build();
    }

    /**
     * An operand of `==` or `!=`: a boxed constant compared with a reference is the reference the bytecode compares.
     */
    private CodeBlock renderComparedOperand(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope,
                                            ExpressionDef operand, ExpressionDef other, CastContext castContext) {
        if (castContext != CastContext.PRIMITIVE_EQUALITY && JavaCasts.comparesBoxed(operand, other)) {
            CodeBlock boxed = literals.renderBoxed(objectDef, methodDef,
                (ExpressionDef.Constant) writtenNode(operand, CastContext.OBJECT_REFERENCE));
            if (boxed != null) {
                return boxed;
            }
        }
        return renderExpressionWithParentheses(objectDef, methodDef, scope, operand, castContext);
    }

    CodeBlock renderVariable(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, VariableDef variableDef) {
        switch (variableDef) {
            case VariableDef.ExceptionVar _ -> {
                // A `$` in the name is no placeholder
                return CodeBlock.of("$L", Objects.requireNonNull(scope.caughtException()));
            }
            case VariableDef.Local localVariableDef -> {
                return CodeBlock.of("$L", scope.resolveLocal(localVariableDef.name()));
            }
            case VariableDef.MethodParameter parameterVariableDef -> {
                if (methodDef == null) {
                    throw new IllegalStateException("Accessing method parameters is not available");
                }
                // The parameter can belong to an enclosing method - a lambda body can capture one
                String name = scope.resolveParameter(parameterVariableDef.name());
                if (name == null) {
                    throw new IllegalStateException("Method: " + methodDef.getName()
                        + " doesn't have parameter: " + parameterVariableDef.name());
                }
                return CodeBlock.of("$L", name);
            }
            case VariableDef.StaticField staticField -> {
                // Always qualified: an unqualified name would resolve to a parameter or local of the same name
                return CodeBlock.of("$T.$L", types.asType(TypeOperations.rawClass(staticField.ownerType()), objectDef), staticField.name());
            }
            case VariableDef.Field field when field.instance() instanceof VariableDef.This && scope.resolveField(field.name()) != null -> {
                // A component a constructor of a record assigns before it calls the canonical one
                return CodeBlock.of("$L", scope.resolveField(field.name()));
            }
            case VariableDef.Field field -> {
                validateFieldAccess(objectDef, field);
                ExpressionDef instance = field.instance();
                // Concatenated as CodeBlocks, not strings re-parsed by CodeBlock.of: a rendered
                // instance expression or the field name can itself contain a literal `$`, which
                // re-parsing as a format string would misread as a placeholder.
                if (!instance.type().equals(field.declaringType())) {
                    return CodeBlock.concat(
                        CodeBlock.of("("),
                        renderExpression(objectDef, methodDef, scope, instance.cast(field.declaringType())),
                        CodeBlock.of(").$L", field.name())
                    );
                }
                return CodeBlock.concat(renderReceiver(objectDef, methodDef, scope, instance), CodeBlock.of(".$L", field.name()));
            }
            case VariableDef.This _ -> {
                if (objectDef == null) {
                    throw new IllegalStateException("Accessing 'this' is not available");
                }
                return CodeBlock.of("this");
            }
            case VariableDef.Super aSuper -> {
                if (objectDef == null) {
                    throw new IllegalStateException("Accessing 'super' is not available");
                }
                // `Type.super` is only valid for a direct superinterface; the bytecode model also names the
                // superclass explicitly (to pick the invokespecial owner), which in source is plain `super`
                if (aSuper.type() != TypeDef.SUPER && aSuper.type().isInterface()) {
                    return CodeBlock.of("$T.super", types.asType(aSuper.type(), objectDef));
                }
                return CodeBlock.of("super");
            }
            case null, default -> throw new IllegalStateException("Unrecognized variable: " + variableDef);
        }
    }

    private static void validateFieldAccess(@Nullable ObjectDef objectDef, VariableDef.Field field) {
        if (objectDef == null) {
            throw new IllegalStateException("Accessing 'this' is not available");
        }
        if (!(field.declaringType() instanceof ClassTypeDef declaringType)
            || !declaringType.getName().equals(objectDef.asTypeDef().getName())) {
            // The field is declared by a different type than the one currently being rendered - e.g. a
            // property accessed on an instance of some other (possibly external, already-compiled) type
            // - so there is nothing in `objectDef` to validate the access against.
            return;
        }
        switch (objectDef) {
            case ClassDef classDef when classDef.hasField(field.name()) -> {
                return;
            }
            case ClassDef classDef ->
                throw new IllegalStateException("Field '" + field.name() + "' is not available in [" + classDef + "]:" + classDef.getFields());
            case EnumDef enumDef when enumDef.hasField(field.name()) -> {
                return;
            }
            case EnumDef enumDef ->
                throw new IllegalStateException("Field '" + field.name() + "' is not available in [" + enumDef.getName() + "]:" + enumDef.getProperties());
            default ->
                throw new IllegalStateException("Field access not supported on the object definition: " + objectDef);
        }
    }

    /**
     * What a value is converted for, see {@link #renderToTarget}.
     */
    enum Target {
        /**
         * Stored where the model declares a type: a local, a field, an array element, a yielded result.
         */
        STORED,
        /**
         * Returned from a method or a lambda.
         */
        RETURNED
    }
}
