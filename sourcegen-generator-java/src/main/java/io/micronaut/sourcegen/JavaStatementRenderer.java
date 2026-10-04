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
import io.micronaut.sourcegen.javapoet.CodeBlock;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.micronaut.sourcegen.JavaTypes.ownerOf;
import static io.micronaut.sourcegen.JavaSourceRules.containsSwitchExpression;

/**
 * The statements of the Java source generator: blocks, the control flow statements, and the simple statements a
 * body is made of.
 *
 * @since 2.3
 */
@Internal
final class JavaStatementRenderer {

    private final JavaExpressionRenderer expressions;
    private final JavaTypeRenderer types;
    private final JavaConversionRules conversionRules;
    private final JavaExceptionRules exceptions;
    private final JavaSourceRules sourceRules;

    JavaStatementRenderer(JavaExpressionRenderer expressions) {
        this.expressions = expressions;
        this.types = expressions.types();
        this.conversionRules = expressions.context().conversions();
        this.exceptions = expressions.context().exceptions();
        this.sourceRules = expressions.context().sourceRules();
    }

    JavaExpressionRenderer expressions() {
        return expressions;
    }

    /**
     * The statements of a body, up to the first that cannot complete normally: the model may append a fallback after
     * an exhaustive statement, which javac rejects as unreachable.
     *
     * @param tailPosition Whether nothing follows the block in the body being rendered
     */
    CodeBlock renderBlock(@Nullable ObjectDef objectDef,
                          @Nullable MethodDef methodDef,
                          RenderScope scope,
                          List<StatementDef> statements,
                          boolean tailPosition) {
        CodeBlock.Builder builder = CodeBlock.builder();
        for (int i = 0; i < statements.size(); i++) {
            StatementDef statement = statements.get(i);
            builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, statement,
                tailPosition && i == statements.size() - 1));
            if (sourceRules.cannotCompleteNormally(statement)) {
                break;
            }
        }
        return builder.build();
    }

    /**
     * The statements of the body of a method, a lambda or an initializer. Where a method called throws a checked
     * exception the body does not declare - which the bytecode writers do not check - the body is enclosed in a try
     * that throws it on unchecked, after the constructor call a constructor starts with.
     */
    CodeBlock renderBody(@Nullable ObjectDef objectDef,
                         @Nullable MethodDef methodDef,
                         RenderScope scope,
                         List<StatementDef> statements) {
        CodeBlock.Builder builder = CodeBlock.builder();
        List<StatementDef> body = statements;
        if (methodDef != null && methodDef.isConstructor()) {
            if (objectDef instanceof RecordDef recordDef && !isCanonical(recordDef, methodDef)
                && (statements.isEmpty() || !JavaExceptionRules.isConstructorInvocation(statements.getFirst())
                || statements.getFirst() instanceof StatementDef.InvokeSuperConstructor)) {
                // Assigning the components itself, where Java requires it to call another constructor
                return JavaRecordConstructors.render(this, recordDef, methodDef, scope, statements);
            }
            body = constructorOrder(objectDef, statements);
            if (!body.isEmpty() && JavaExceptionRules.isConstructorInvocation(body.getFirst())) {
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, body.getFirst(), body.size() == 1));
                body = body.subList(1, body.size());
            }
        }
        if (exceptions.thrown(body, false, objectDef, methodDef).stream().allMatch(type -> exceptions.handledIn(scope, type))) {
            return builder.add(renderBlock(objectDef, methodDef, scope, body, true)).build();
        }
        RenderScope enclosed = scope.nestedHandling(List.of(TypeDef.of(Throwable.class)));
        String throwable = enclosed.declareFresh("throwable");
        return builder.add("try {\n").indent()
            .add(renderBlock(objectDef, methodDef, enclosed, body, true))
            .unindent().add("} catch ($T $L) {\n", Throwable.class, throwable).indent()
            .addStatement("throw $L", sneakyThrow(CodeBlock.of("$L", throwable)))
            .unindent().add("}\n")
            .build();
    }

    /**
     * The statements of a constructor in the order the bytecode writers run them: the constructor call first, where
     * the class initializes instance fields - which run after it - and a statement of the model precedes it.
     */
    private static List<StatementDef> constructorOrder(@Nullable ObjectDef objectDef, List<StatementDef> statements) {
        boolean initializes = objectDef instanceof ClassDef classDef && classDef.getFields().stream()
            .anyMatch(field -> !field.getModifiers().contains(javax.lang.model.element.Modifier.STATIC) && field.getInitializer().isPresent());
        int index = 0;
        while (index < statements.size() && !JavaExceptionRules.isConstructorInvocation(statements.get(index))) {
            index++;
        }
        if (!initializes || index == 0 || index == statements.size()) {
            return statements;
        }
        List<StatementDef> ordered = new ArrayList<>(statements);
        ordered.addFirst(ordered.remove(index));
        return ordered;
    }

    private static boolean isCanonical(RecordDef recordDef, MethodDef constructor) {
        List<TypeDef> components = recordDef.getProperties().stream().map(PropertyDef::getType).toList();
        return components.equals(constructor.getParameters().stream().map(ParameterDef::getType).toList());
    }

    /**
     * A static initializer. One that returns is a labeled block the return breaks out of, and one that cannot complete
     * normally - which javac rejects of an initializer, where the JVM fails the initialization of the class - is the
     * body of an `if (true)`, which can.
     *
     * @param classDef    The class
     * @param initializer The statement of the initializer
     * @return The code of the static block
     */
    CodeBlock renderInitializer(ClassDef classDef, StatementDef initializer) {
        RenderScope scope = RenderScope.root(null).body(List.of(initializer));
        boolean[] returns = {false};
        JavaLambdaRules.forEachStatement(List.of(initializer), statement -> returns[0] |= statement instanceof StatementDef.Return);
        String label = returns[0] ? scope.allocate("initializer") : null;
        if (label != null) {
            scope.returning(label);
        }
        CodeBlock body = renderBody(classDef, null, scope, List.of(initializer));
        if (label != null) {
            body = CodeBlock.builder().add("$L: {\n", label).indent().add(body).unindent().add("}\n").build();
        }
        if (sourceRules.cannotCompleteNormally(initializer)) {
            body = CodeBlock.builder().add("if (true) {\n").indent().add(body).unindent().add("}\n").build();
        }
        return body;
    }

    /**
     * Whether an expression is a conditional or a switch of void results: no expression Java has, it is written as the
     * statement of the results.
     *
     * @param expression The expression
     * @return true if it is written as a statement
     */
    static boolean isVoidBranching(ExpressionDef expression) {
        return TypeDef.VOID.equals(expression.type())
            && (expression instanceof ExpressionDef.IfElse || expression instanceof ExpressionDef.Switch);
    }

    /**
     * A void expression as a statement: a conditional as an `if`, a switch as a switch statement.
     */
    private CodeBlock renderVoid(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, RenderScope scope, ExpressionDef expression) {
        if (expression instanceof ExpressionDef.IfElse conditional && TypeDef.VOID.equals(conditional.type())) {
            return CodeBlock.builder()
                .add("if (").add(expressions.renderExpression(objectDef, methodDef, scope, conditional.condition())).add(") {\n")
                .indent().add(renderVoid(objectDef, methodDef, scope, conditional.ifExpression())).unindent()
                .add("} else {\n")
                .indent().add(renderVoid(objectDef, methodDef, scope, conditional.elseExpression())).unindent()
                .add("}\n")
                .build();
        }
        CodeBlock rendered = expressions.renderExpression(objectDef, methodDef, scope, expression);
        // Both render statements of their own, and JavaPoet rejects nesting its statement markers
        return containsSwitchExpression(expression) || sourceRules.containsBlockBodyLambda(expression, objectDef)
            ? CodeBlock.builder().add(rendered).add(";\n").build()
            : CodeBlock.builder().addStatement(rendered).build();
    }

    /**
     * An exception thrown as it is, through a helper javac does not check for the exceptions it throws.
     */
    CodeBlock sneakyThrow(CodeBlock throwable) {
        ObjectDef topLevel = exceptions.useSneakyThrow();
        return CodeBlock.of("$T.$L($L)", types.asType(topLevel.asTypeDef(), null), JavaExceptionRules.SNEAKY_THROW, throwable);
    }

    CodeBlock renderStatementCodeBlock(@Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       RenderScope scope,
                                       StatementDef statementDef) {
        return renderStatementCodeBlock(objectDef, methodDef, scope, statementDef, false);
    }

    /**
     * @param tailPosition Whether nothing follows the statement in the body being rendered, so that returning is
     *                     what falling out of it does anyway
     */
    CodeBlock renderStatementCodeBlock(@Nullable ObjectDef objectDef,
                                       @Nullable MethodDef methodDef,
                                       RenderScope scope,
                                       StatementDef statementDef,
                                       boolean tailPosition) {
        CodeBlock copies = renderCaptureCopies(objectDef, methodDef, scope, statementDef);
        CodeBlock rendered = renderCompound(objectDef, methodDef, scope, statementDef, tailPosition);
        return copies.isEmpty() ? rendered : CodeBlock.concat(copies, rendered);
    }

    /**
     * The copies of the locals the lambdas of a statement capture which the body assigns: the lambda captures the
     * value the local has where it is created, as the bytecode passes it, from a copy that is effectively final.
     */
    private CodeBlock renderCaptureCopies(@Nullable ObjectDef objectDef,
                                         @Nullable MethodDef methodDef,
                                         RenderScope scope,
                                         StatementDef statementDef) {
        CodeBlock.Builder builder = CodeBlock.builder();
        for (ExpressionDef.Lambda lambda : JavaLambdaRules.createdLambdas(statementDef)) {
            Map<String, String> copies = new LinkedHashMap<>();
            JavaLambdaRules.capturedLocals(lambda).forEach((name, type) -> {
                if (scope.isAssignedInBody(name)) {
                    String copy = scope.declareFresh(name);
                    builder.addStatement("$T $L = $L", types.asType(type, objectDef, methodDef), copy, scope.resolveLocal(name));
                    copies.put(name, copy);
                }
            });
            if (!copies.isEmpty()) {
                scope.recordCopies(lambda, copies);
            }
        }
        return builder.build();
    }

    private CodeBlock renderCompound(@Nullable ObjectDef objectDef,
                                     @Nullable MethodDef methodDef,
                                     RenderScope scope,
                                     StatementDef statementDef,
                                     boolean tailPosition) {
        switch (statementDef) {
            case StatementDef.Multi statements -> {
                return renderBlock(objectDef, methodDef, scope, statements.statements(), tailPosition);
            }
            case StatementDef.Try tryStatement -> {
                return renderTry(objectDef, methodDef, scope, tryStatement, tailPosition);
            }
            case StatementDef.Synchronized s -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("synchronized (");
                builder.add(expressions.renderExpression(objectDef, methodDef, scope, s.monitor(), true));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, s.statement(), tailPosition));
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.If ifStatement -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("if (");
                builder.add(expressions.renderExpression(objectDef, methodDef, scope, ifStatement.condition()));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, ifStatement.statement(), tailPosition));
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.IfElse ifStatement -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("if (");
                builder.add(expressions.renderExpression(objectDef, methodDef, scope, ifStatement.condition()));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, ifStatement.statement(), tailPosition));
                builder.unindent();
                builder.add("} else {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, ifStatement.elseStatement(), tailPosition));
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.Switch aSwitch -> {
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("switch (");
                builder.add(expressions.renderExpression(objectDef, methodDef, scope, aSwitch.expression()));
                builder.add(") {\n");
                builder.indent();
                for (Map.Entry<ExpressionDef.Constant, StatementDef> e : aSwitch.cases().entrySet()) {
                    builder.add("case ");
                    builder.add(expressions.renderExpression(objectDef, methodDef, scope, e.getKey()));
                    builder.add(" -> {\n");
                    builder.indent();
                    builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, e.getValue(), tailPosition));
                    builder.unindent();
                    builder.add("}\n");
                }
                if (aSwitch.defaultCase() != null) {
                    builder.add("default -> {\n");
                    builder.indent();
                    builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, aSwitch.defaultCase(), tailPosition));
                    builder.unindent();
                    builder.add("}\n");
                }
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.While aWhile -> {
                if (sourceRules.isConstantFalse(aWhile.expression())) {
                    // Never entered, where javac rejects its body as unreachable
                    return CodeBlock.of("");
                }
                CodeBlock.Builder builder = CodeBlock.builder();
                builder.add("while (");
                builder.add(expressions.renderExpression(objectDef, methodDef, scope, aWhile.expression()));
                builder.add(") {\n");
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, aWhile.statement()));
                builder.unindent();
                builder.add("}\n");
                return builder.build();
            }
            case StatementDef.Return aReturn when aReturn.expression() != null
                && TypeDef.VOID.equals(aReturn.expression().type()) -> {
                // A void invocation cannot be returned in source. Where the statement is not in tail position - a
                // branch of a conditional, say - the call is followed by the return it stands for, which execution
                // would otherwise fall through
                CodeBlock.Builder builder = CodeBlock.builder()
                    .add(renderVoid(objectDef, methodDef, scope, aReturn.expression()));
                if (!tailPosition) {
                    builder.addStatement(returnWithout(scope));
                }
                return builder.build();
            }

            case null, default -> {
                CodeBlock statement = renderStatement(objectDef, methodDef, scope, statementDef);
                // Both render statements of their own, and JavaPoet rejects nesting its statement markers
                if (statementDef != null
                    && (containsSwitchExpression(statementDef) || sourceRules.containsBlockBodyLambda(statementDef, objectDef))) {
                    return CodeBlock.builder()
                        .add(statement)
                        .add(";\n")
                        .build();
                }
                return CodeBlock.builder()
                    .addStatement(statement)
                    .build();
            }
        }
    }

    /**
     * A try, without a catch that is dead - of a subclass of an exception an earlier catch catches. From the first
     * catch of a checked exception javac does not see the body throw, which the bytecode may still catch, the catches
     * are one of any throwable dispatching on its class, which throws the others on unchecked.
     */
    private CodeBlock renderTry(@Nullable ObjectDef objectDef,
                                @Nullable MethodDef methodDef,
                                RenderScope scope,
                                StatementDef.Try tryStatement,
                                boolean tailPosition) {
        List<StatementDef.Try.Catch> catches = exceptions.liveCatches(tryStatement);
        if (catches.isEmpty() && tryStatement.finallyStatement() == null) {
            return renderStatementCodeBlock(objectDef, methodDef, scope, tryStatement.statement(), tailPosition);
        }
        List<TypeDef> thrown = exceptions.thrown(List.of(tryStatement.statement()), true, objectDef, methodDef);
        int dispatched = 0;
        while (dispatched < catches.size() && exceptions.isCatchable(catches.get(dispatched).exception(), thrown)) {
            dispatched++;
        }
        List<TypeDef> caught = dispatched < catches.size() ? List.of(TypeDef.of(Throwable.class))
            : catches.stream().<TypeDef>map(StatementDef.Try.Catch::exception).toList();
        CodeBlock.Builder builder = CodeBlock.builder();
        builder.add("try {\n");
        builder.indent();
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope.nestedHandling(caught), tryStatement.statement(), tailPosition));
        builder.unindent();
        int[] index = {0};
        for (StatementDef.Try.Catch aCatch : catches.subList(0, dispatched)) {
            RenderScope catchScope = scope.catching(objectDef, aCatch.statement(), index);
            builder.add(CodeBlock.of("} catch ($T $L) {\n", types.asType(aCatch.exception(), objectDef), catchScope.caughtException()));
            builder.indent();
            builder.add(renderStatementCodeBlock(objectDef, methodDef, catchScope, aCatch.statement(), tailPosition));
            builder.unindent();
        }
        if (dispatched < catches.size()) {
            RenderScope dispatchScope = scope.nested(null);
            String throwable = dispatchScope.declareFresh("throwable");
            builder.add("} catch ($T $L) {\n", Throwable.class, throwable);
            builder.indent();
            for (StatementDef.Try.Catch aCatch : catches.subList(dispatched, catches.size())) {
                RenderScope catchScope = dispatchScope.catching(objectDef, aCatch.statement(), index);
                builder.add("$L ($L instanceof $T $L) {\n", aCatch == catches.get(dispatched) ? "if" : "} else if",
                    throwable, types.asType(aCatch.exception(), objectDef), catchScope.caughtException());
                builder.indent();
                builder.add(renderStatementCodeBlock(objectDef, methodDef, catchScope, aCatch.statement(), tailPosition));
                builder.unindent();
            }
            builder.add("} else {\n");
            builder.indent();
            builder.addStatement("throw $L", sneakyThrow(CodeBlock.of("$L", throwable)));
            builder.unindent();
            builder.add("}\n");
            builder.unindent();
        }
        if (tryStatement.finallyStatement() != null) {
            builder.add("} finally {\n");
            builder.indent();
            // Never the tail: a return here discards an exception or a return of the try, which falling out
            // of the block does not
            builder.add(renderStatementCodeBlock(objectDef, methodDef, scope, tryStatement.finallyStatement(), false));
            builder.unindent();
        }
        builder.add("}\n");
        return builder.build();
    }

    private CodeBlock renderStatement(@Nullable ObjectDef objectDef,
                                      @Nullable MethodDef methodDef,
                                      RenderScope scope,
                                      StatementDef statementDef) {
        switch (statementDef) {
            case StatementDef.InvokeSuperConstructor invokeConstructor -> {
                // A super constructor call is always the unqualified `super(...)`, even when the
                // `VariableDef.Super` carries an explicit type used only to resolve the constructor
                // overload for bytecode generation; `Type.super(...)` is not valid Java syntax here.
                return CodeBlock.concat(
                    CodeBlock.of("super"),
                    CodeBlock.of("("),
                    expressions.renderInvocationArguments(objectDef, methodDef, scope,
                        ownerOf(objectDef, invokeConstructor.superInstance().type()),
                        invokeConstructor.method(), invokeConstructor.values()),
                    CodeBlock.of(")")
                );
            }
            case StatementDef.Throw aThrow -> {
                CodeBlock thrown = expressions.renderExpression(objectDef, methodDef, scope, aThrow.expression());
                TypeDef type = conversionRules.sourceTypeOf(aThrow.expression(), methodDef, objectDef);
                if (exceptions.isChecked(type) && !exceptions.handledIn(scope, type)) {
                    // A checked exception the bytecode throws where nothing declares or catches it
                    thrown = sneakyThrow(thrown);
                }
                return CodeBlock.concat(CodeBlock.of("throw "), thrown);
            }
            case StatementDef.Return aReturn -> {
                return renderReturn(objectDef, methodDef, scope, aReturn);
            }
            case StatementDef.Assign assign -> {
                return CodeBlock.concat(
                    expressions.renderExpression(objectDef, methodDef, scope, assign.variable()),
                    CodeBlock.of(" = "),
                    expressions.renderStored(objectDef, methodDef, scope, assign.variable().type(), assign.expression())
                );
            }
            case StatementDef.PutField putField -> {
                return CodeBlock.concat(
                    expressions.renderExpression(objectDef, methodDef, scope, putField.field()),
                    CodeBlock.of(" = "),
                    expressions.renderStored(objectDef, methodDef, scope, putField.field().type(), putField.expression())
                );
            }
            case StatementDef.PutStaticField putStaticField -> {
                // A blank final static field can only be assigned by its unqualified name, and
                // `ThisClass.field = ...` is illegal even where `ThisClass` is the class being written. Every other
                // field is assigned by its qualified name, which no local of the same name can take over
                CodeBlock target = methodDef == null && objectDef != null
                    && putStaticField.field().ownerType().getName().equals(objectDef.asTypeDef().getName())
                    && sourceRules.keepsFinal(objectDef, putStaticField.field().name())
                    ? CodeBlock.of("$L", putStaticField.field().name())
                    : expressions.renderExpression(objectDef, methodDef, scope, putStaticField.field());
                return CodeBlock.concat(
                    target,
                    CodeBlock.of(" = "),
                    expressions.renderStored(objectDef, methodDef, scope, putStaticField.field().type(), putStaticField.expression())
                );
            }
            case StatementDef.DefineAndAssign assign -> {
                // In scope of its own initializer: a lambda in it cannot name a parameter like the variable
                String name = scope.declareLocal(assign.variable().name());
                return CodeBlock.concat(
                    CodeBlock.of("$T $L", types.asType(assign.variable().type(), objectDef, methodDef), name),
                    CodeBlock.of(" = "),
                    expressions.renderStored(objectDef, methodDef, scope, assign.variable().type(), assign.expression())
                );
            }
            case ExpressionDef expressionDef -> {
                return expressions.renderExpression(objectDef, methodDef, scope, expressionDef);
            }
            case null, default -> throw new IllegalStateException("Unrecognized statement: " + statementDef);
        }
    }

    private CodeBlock renderReturn(@Nullable ObjectDef objectDef,
                                   @Nullable MethodDef methodDef,
                                   RenderScope scope,
                                   StatementDef.Return aReturn) {
        ExpressionDef returned = aReturn.expression();
        if (returned == null) {
            return returnWithout(scope);
        }
        TypeDef yieldType = scope.yieldType();
        if (yieldType != null) {
            // A return nested in a block of a switch expression - in a try, a loop - yields its result, converted to
            // the type of the switch where Java would promote the results as numbers
            return CodeBlock.concat(CodeBlock.of("yield "), scope.yieldsConverted()
                ? expressions.renderExpression(objectDef, methodDef, scope, returned.cast(yieldType))
                : expressions.renderStored(objectDef, methodDef, scope, yieldType, returned));
        }
        if (returned.type().equals(TypeDef.VOID)) {
            // Returning a void invocation is a plain call in source
            return expressions.renderExpression(objectDef, methodDef, scope, returned);
        }
        return CodeBlock.concat(CodeBlock.of("return "), methodDef == null
            ? expressions.renderExpression(objectDef, null, scope, returned)
            : expressions.renderToTarget(objectDef, methodDef, scope, methodDef.getReturnType(), returned, JavaExpressionRenderer.Target.RETURNED));
    }

    /**
     * A return without a value: a static initializer breaks out of the block it is written as.
     */
    private static CodeBlock returnWithout(RenderScope scope) {
        String label = scope.returnLabel();
        return label == null ? CodeBlock.of("return") : CodeBlock.of("break $L", label);
    }

    /**
     * @param converted Whether each value yielded is converted to the type of the switch
     */
    CodeBlock renderSwitchYieldCase(@Nullable ObjectDef objectDef,
                                    @Nullable MethodDef methodDef,
                                    RenderScope scope,
                                    ExpressionDef.SwitchYieldCase switchYieldCase,
                                    boolean converted) {
        CodeBlock.Builder builder = CodeBlock.builder();
        builder.add("{\n");
        builder.indent();
        StatementDef statement = switchYieldCase.statement();
        List<StatementDef> flatten = statement.flatten();
        if (flatten.isEmpty()) {
            throw new IllegalStateException("SwitchYieldCase did not return any statements");
        }
        if (!sourceRules.cannotCompleteNormally(statement)) {
            // The bytecode would load a result no path stored
            throw new IllegalStateException("A SwitchYieldCase should end with a return or a throw. Found: " + flatten.getLast());
        }
        // The block's returns, wherever they are nested, yield the result of the switch
        builder.add(renderStatementCodeBlock(objectDef, methodDef, scope.yielding(switchYieldCase.type(), converted), statement));
        builder.unindent();
        builder.add("}");
        String str = builder.build().toString();
        // Render the body to prevent nested statements
        return CodeBlock.ofWithoutFormat(str);
    }
}
