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
import io.micronaut.sourcegen.generator.InvokedSignature;
import io.micronaut.sourcegen.generator.OverrideResolver;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.JavaIdioms;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What Java makes of a statement: whether it can complete normally, whether it has to be written as a statement of
 * its own, and where a blank final static field is definitely assigned.
 *
 * @since 2.2
 */
@Internal
final class JavaSourceRules {

    private JavaSourceRules() {
    }

    static boolean declaresField(@Nullable ObjectDef objectDef, String name) {
        List<FieldDef> fields = switch (objectDef) {
            case ClassDef classDef -> classDef.getFields();
            case EnumDef enumDef -> enumDef.getFields();
            case null, default -> List.of();
        };
        return fields.stream().anyMatch(field -> field.getName().equals(name));
    }

    /**
     * @param lambda The lambda
     * @return The expression of a single expression body, or {@code null} for a block body
     */
    @Nullable
    static ExpressionDef singleExpressionBody(Lambda lambda) {
        List<StatementDef> statements = lambda.implementation().getStatements();
        if (statements.size() == 1 && statements.get(0) instanceof StatementDef.Return(ExpressionDef expression)) {
            return expression;
        }
        return null;
    }

    static boolean containsBlockBodyLambda(StatementDef statementDef) {
        return statementDef.nestedExpressionsStream().anyMatch(JavaSourceRules::containsBlockBodyLambda);
    }

    static boolean containsBlockBodyLambda(ExpressionDef expressionDef) {
        if (expressionDef instanceof Lambda lambda) {
            // A lambda does not expose its body as nested expressions, so descend into it explicitly
            return singleExpressionBody(lambda) == null
                || lambda.implementation().getStatements().stream().anyMatch(JavaSourceRules::containsBlockBodyLambda);
        }
        return expressionDef.nestedExpressionsStream().anyMatch(JavaSourceRules::containsBlockBodyLambda);
    }

    static boolean containsSwitchExpression(StatementDef statementDef) {
        return statementDef.nestedExpressionsStream().anyMatch(JavaSourceRules::containsSwitchExpression);
    }

    static boolean containsSwitchExpression(ExpressionDef expressionDef) {
        return expressionDef instanceof ExpressionDef.Switch
            || expressionDef.nestedExpressionsStream().anyMatch(JavaSourceRules::containsSwitchExpression);
    }

    /**
     * Whether a blank final static field of the definition is definitely assigned exactly once by its static
     * initializer, which is what Java requires of one - and what lets the field keep its {@code final} modifier and
     * be assigned by its unqualified name.
     *
     * @param objectDef The definition
     * @param fieldName The field name
     * @return true if the field keeps its {@code final} modifier
     */
    static boolean keepsFinal(@Nullable ObjectDef objectDef, String fieldName) {
        if (!(objectDef instanceof ClassDef classDef) || classDef.getStaticInitializer() == null) {
            return false;
        }
        boolean blankFinal = classDef.getFields().stream()
            .filter(field -> field.getName().equals(fieldName))
            .anyMatch(field -> field.getModifiers().contains(Modifier.STATIC)
                && field.getModifiers().contains(Modifier.FINAL)
                && field.getInitializer().isEmpty());
        if (!blankFinal) {
            return false;
        }
        // A local of the same name takes over the unqualified assignment the field would need
        if (declaresLocal(classDef.getStaticInitializer(), fieldName)) {
            return false;
        }
        Assignment assignment = assignmentOf(classDef.getStaticInitializer(), classDef.asTypeDef().getName(), fieldName);
        return assignment.definite() && !assignment.repeatable();
    }

    /**
     * Whether a final instance field of the definition is assigned as Java requires of one: by its initializer and no
     * constructor, or, blank, exactly once by every constructor that does not delegate to another - which is what lets
     * the field keep its {@code final} modifier. A model written for bytecode can assign it in one constructor of
     * several, or conditionally, which the verifier accepts.
     *
     * @param objectDef The definition
     * @param field     The field
     * @return true if the field keeps its {@code final} modifier
     */
    static boolean keepsFinal(ObjectDef objectDef, FieldDef field) {
        List<MethodDef> constructors = objectDef.getMethods().stream().filter(MethodDef::isConstructor).toList();
        Predicate<StatementDef> assigns = statement -> statement instanceof StatementDef.PutField put
            && put.field().name().equals(field.getName()) && put.field().instance() instanceof VariableDef.This;
        if (field.getInitializer().isPresent()) {
            return constructors.stream().noneMatch(constructor -> assignmentOf(bodyOf(constructor), assigns).possible());
        }
        if (constructors.isEmpty()) {
            return false;
        }
        for (MethodDef constructor : constructors) {
            Assignment assignment = assignmentOf(bodyOf(constructor), assigns);
            if (delegates(constructor) ? assignment.possible() : !assignment.definite() || assignment.repeatable()) {
                return false;
            }
        }
        return true;
    }

    private static StatementDef bodyOf(MethodDef method) {
        return StatementDef.multi(method.getStatements());
    }

    /**
     * Whether a constructor delegates to another of the class, which assigns the final fields for it.
     */
    private static boolean delegates(MethodDef constructor) {
        return constructor.getStatements().stream().anyMatch(statement -> statement instanceof ExpressionDef.InvokeInstanceMethod invocation
            && invocation.method().isConstructor() && invocation.instance() instanceof VariableDef.This);
    }

    static boolean declaresLocal(@Nullable StatementDef statement, String name) {
        return switch (statement) {
            case null -> false;
            case StatementDef.DefineAndAssign define -> define.variable().name().equals(name);
            case StatementDef.Multi multi -> multi.statements().stream().anyMatch(child -> declaresLocal(child, name));
            case StatementDef.If anIf -> declaresLocal(anIf.statement(), name);
            case StatementDef.IfElse ifElse -> declaresLocal(ifElse.statement(), name)
                || declaresLocal(ifElse.elseStatement(), name);
            case StatementDef.Switch aSwitch -> declaresLocal(aSwitch.defaultCase(), name)
                || aSwitch.cases().values().stream().anyMatch(aCase -> declaresLocal(aCase, name));
            case StatementDef.While aWhile -> declaresLocal(aWhile.statement(), name);
            case StatementDef.Synchronized aSynchronized -> declaresLocal(aSynchronized.statement(), name);
            case StatementDef.Try aTry -> declaresLocal(aTry.statement(), name)
                || declaresLocal(aTry.finallyStatement(), name)
                || aTry.catches().stream().anyMatch(aCatch -> declaresLocal(aCatch.statement(), name));
            default -> false;
        };
    }

    private static Assignment assignmentOf(@Nullable StatementDef statement, String ownerName, String fieldName) {
        // A field of another type shares nothing with this one but its name
        return assignmentOf(statement, child -> child instanceof StatementDef.PutStaticField put
            && put.field().name().equals(fieldName) && put.field().ownerType().getName().equals(ownerName));
    }

    private static Assignment assignmentOf(@Nullable StatementDef statement, Predicate<StatementDef> assigns) {
        return switch (statement) {
            case null -> Assignment.NONE;
            // No path completes normally past it, so the field is as assigned as it needs to be there
            case StatementDef.Throw aThrow -> Assignment.VACUOUS;
            case StatementDef.PutStaticField put -> assigns.test(put) ? Assignment.ONCE : Assignment.NONE;
            case StatementDef.PutField put -> assigns.test(put) ? Assignment.ONCE : Assignment.NONE;
            case StatementDef.Multi multi -> {
                boolean definite = false;
                boolean possible = false;
                boolean repeatable = false;
                for (StatementDef child : multi.statements()) {
                    Assignment assignment = assignmentOf(child, assigns);
                    repeatable |= assignment.repeatable() || (possible && assignment.possible());
                    definite |= assignment.definite();
                    possible |= assignment.possible();
                }
                yield new Assignment(definite, possible, repeatable);
            }
            case StatementDef.If anIf -> {
                Assignment assignment = assignmentOf(anIf.statement(), assigns);
                yield new Assignment(false, assignment.possible(), assignment.repeatable());
            }
            case StatementDef.IfElse ifElse -> {
                // The branches are mutually exclusive: assigning in each of them assigns the field exactly once
                Assignment then = assignmentOf(ifElse.statement(), assigns);
                Assignment otherwise = assignmentOf(ifElse.elseStatement(), assigns);
                yield new Assignment(then.definite() && otherwise.definite(), then.possible() || otherwise.possible(),
                    then.repeatable() || otherwise.repeatable());
            }
            case StatementDef.Switch aSwitch -> {
                boolean definite = aSwitch.defaultCase() != null
                    && assignmentOf(aSwitch.defaultCase(), assigns).definite();
                boolean possible = assignmentOf(aSwitch.defaultCase(), assigns).possible();
                boolean repeatable = assignmentOf(aSwitch.defaultCase(), assigns).repeatable();
                for (StatementDef aCase : aSwitch.cases().values()) {
                    Assignment assignment = assignmentOf(aCase, assigns);
                    definite &= assignment.definite();
                    possible |= assignment.possible();
                    repeatable |= assignment.repeatable();
                }
                yield new Assignment(definite, possible, repeatable);
            }
            case StatementDef.While aWhile -> {
                // An iteration could assign what the one before it did
                Assignment assignment = assignmentOf(aWhile.statement(), assigns);
                yield new Assignment(false, assignment.possible(), assignment.possible() || assignment.repeatable());
            }
            case StatementDef.Synchronized aSynchronized -> assignmentOf(aSynchronized.statement(), assigns);
            case StatementDef.Try aTry -> {
                Assignment body = assignmentOf(aTry.statement(), assigns);
                Assignment aFinally = assignmentOf(aTry.finallyStatement(), assigns);
                boolean catchesPossible = false;
                boolean catchesDefinite = true;
                boolean repeatable = body.repeatable() || aFinally.repeatable();
                for (StatementDef.Try.Catch aCatch : aTry.catches()) {
                    Assignment assignment = assignmentOf(aCatch.statement(), assigns);
                    catchesPossible |= assignment.possible();
                    catchesDefinite &= assignment.definite();
                    repeatable |= assignment.repeatable();
                }
                // A path through the body, or through a catch, may have assigned the field before reaching the
                // next one, which is what Java reports as possibly already assigned
                repeatable |= (body.possible() || catchesPossible) && aFinally.possible()
                    || body.possible() && catchesPossible;
                boolean definite = aFinally.definite() || (body.definite() && catchesDefinite);
                yield new Assignment(definite, body.possible() || catchesPossible || aFinally.possible(), repeatable);
            }
            default -> Assignment.NONE;
        };
    }

    static boolean cannotCompleteNormally(StatementDef statementDef) {
        return switch (statementDef) {
            case StatementDef.Return _, StatementDef.Throw _ -> true;
            // A block ends with a statement that cannot complete, or already stops at one
            case StatementDef.Multi multi -> multi.statements().stream().anyMatch(JavaSourceRules::cannotCompleteNormally);
            case StatementDef.IfElse ifElse ->
                cannotCompleteNormally(ifElse.statement()) && cannotCompleteNormally(ifElse.elseStatement());
            case StatementDef.Switch aSwitch -> aSwitch.defaultCase() != null
                && cannotCompleteNormally(aSwitch.defaultCase())
                && aSwitch.cases().values().stream().allMatch(JavaSourceRules::cannotCompleteNormally);
            // A finally that cannot complete ends the try, whatever the try does; one that can, completes as the try
            // and the catches that are written do
            case StatementDef.Try aTry -> aTry.finallyStatement() != null && cannotCompleteNormally(aTry.finallyStatement())
                || cannotCompleteNormally(aTry.statement())
                && liveCatches(aTry, null).stream().allMatch(aCatch -> cannotCompleteNormally(aCatch.statement()));
            case StatementDef.Synchronized aSynchronized -> cannotCompleteNormally(aSynchronized.statement());
            // The model has no break: `while (true)` only ends by a return or a throw
            case StatementDef.While aWhile -> isConstantTrue(aWhile.expression());
            default -> false;
        };
    }

    private static boolean isConstantTrue(ExpressionDef expression) {
        return Boolean.TRUE.equals(constantValue(expression));
    }

    static boolean isConstantFalse(ExpressionDef expression) {
        return Boolean.FALSE.equals(constantValue(expression));
    }

    /**
     * The value of a constant expression as Java evaluates one, which is what decides whether a loop over it can
     * complete: a literal, and the negations, conjunctions and comparisons of constant expressions.
     */
    @Nullable
    private static Object constantValue(ExpressionDef expression) {
        return switch (expression) {
            case ExpressionDef.Constant constant -> constant.value() instanceof Boolean || constant.value() instanceof Number
                || constant.value() instanceof Character ? constant.value() : null;
            // A cast to a primitive, or one that is not written, keeps a constant expression one
            case ExpressionDef.Cast cast -> constantValue(cast.expressionDef());
            case ExpressionDef.IsTrue isTrue -> constantValue(isTrue.expression());
            case ExpressionDef.IsFalse isFalse -> constantValue(isFalse.expression()) instanceof Boolean value ? !value : null;
            case ExpressionDef.And and -> constantValue(and.left()) instanceof Boolean left
                && constantValue(and.right()) instanceof Boolean right ? left && right : null;
            case ExpressionDef.Or or -> constantValue(or.left()) instanceof Boolean left
                && constantValue(or.right()) instanceof Boolean right ? left || right : null;
            case ExpressionDef.ComparisonOperation comparison -> compared(comparison.opType(),
                constantValue(comparison.left()), constantValue(comparison.right()));
            case ExpressionDef.EqualsReferentially equals -> compared(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO,
                constantValue(equals.instance()), constantValue(equals.other()));
            case ExpressionDef.NotEqualsReferentially notEquals -> compared(ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO,
                constantValue(notEquals.instance()), constantValue(notEquals.other()));
            default -> null;
        };
    }

    @Nullable
    private static Boolean compared(ExpressionDef.ComparisonOperation.OpType op, @Nullable Object left, @Nullable Object right) {
        if (left instanceof Boolean && right instanceof Boolean) {
            return switch (op) {
                case EQUAL_TO -> left.equals(right);
                case NOT_EQUAL_TO -> !left.equals(right);
                default -> null;
            };
        }
        if (!(left instanceof Number || left instanceof Character) || !(right instanceof Number || right instanceof Character)) {
            return null;
        }
        int order = Double.compare(left instanceof Character c ? c : ((Number) left).doubleValue(),
            right instanceof Character c ? c : ((Number) right).doubleValue());
        return switch (op) {
            case EQUAL_TO -> order == 0;
            case NOT_EQUAL_TO -> order != 0;
            case GREATER_THAN -> order > 0;
            case LESS_THAN -> order < 0;
            case GREATER_THAN_OR_EQUAL -> order >= 0;
            case LESS_THAN_OR_EQUAL -> order <= 0;
        };
    }

    static void validateFieldAccess(@Nullable ObjectDef objectDef, VariableDef.Field field) {
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
     * The modifiers the accessors of a property of an interface are written with: public and abstract, as the
     * bytecode writer declares them, unless the property makes them default or static.
     *
     * @param property The property
     * @return The modifiers
     */
    static Modifier[] interfacePropertyModifiers(PropertyDef property) {
        Set<Modifier> modifiers = new LinkedHashSet<>(property.getModifiers());
        modifiers.removeAll(List.of(Modifier.PRIVATE, Modifier.PROTECTED, Modifier.FINAL, Modifier.ABSTRACT));
        modifiers.add(Modifier.PUBLIC);
        if (!modifiers.contains(Modifier.DEFAULT) && !modifiers.contains(Modifier.STATIC)) {
            modifiers.add(Modifier.ABSTRACT);
        }
        return modifiers.toArray(Modifier[]::new);
    }

    /**
     * Whether a statement returns at any depth of its blocks - not of the lambdas or switch expressions within
     * them, which return of their own. An initializer that does is written in a labelled block its returns break
     * out of, as Java has no return outside a method.
     *
     * @param statement The statement
     * @return true if the statement contains a return
     */
    static boolean containsReturn(@Nullable StatementDef statement) {
        return switch (statement) {
            case null -> false;
            case StatementDef.Return _ -> true;
            case StatementDef.Multi multi -> multi.statements().stream().anyMatch(JavaSourceRules::containsReturn);
            case StatementDef.If anIf -> containsReturn(anIf.statement());
            case StatementDef.IfElse ifElse -> containsReturn(ifElse.statement()) || containsReturn(ifElse.elseStatement());
            case StatementDef.Switch aSwitch -> containsReturn(aSwitch.defaultCase())
                || aSwitch.cases().values().stream().anyMatch(JavaSourceRules::containsReturn);
            case StatementDef.While aWhile -> containsReturn(aWhile.statement());
            case StatementDef.Synchronized aSynchronized -> containsReturn(aSynchronized.statement());
            case StatementDef.Try aTry -> containsReturn(aTry.statement())
                || containsReturn(aTry.finallyStatement())
                || aTry.catches().stream().anyMatch(aCatch -> containsReturn(aCatch.statement()));
            default -> false;
        };
    }

    /**
     * The locals a body assigns after declaring them, which are not effectively final: a lambda cannot capture one.
     * The body of a lambda within it declares and assigns locals of its own.
     *
     * @param statements The statements of the body
     * @return The names of the locals
     */
    static Set<String> reassignedLocals(List<StatementDef> statements) {
        Set<String> names = new LinkedHashSet<>();
        statements.forEach(statement -> collectReassigned(statement, names));
        return names;
    }

    private static void collectReassigned(@Nullable StatementDef statement, Set<String> names) {
        switch (statement) {
            case null -> {
            }
            case StatementDef.Assign assign -> names.add(assign.variable().name());
            case StatementDef.Multi multi -> multi.statements().forEach(child -> collectReassigned(child, names));
            case StatementDef.If anIf -> collectReassigned(anIf.statement(), names);
            case StatementDef.IfElse ifElse -> {
                collectReassigned(ifElse.statement(), names);
                collectReassigned(ifElse.elseStatement(), names);
            }
            case StatementDef.Switch aSwitch -> {
                collectReassigned(aSwitch.defaultCase(), names);
                aSwitch.cases().values().forEach(aCase -> collectReassigned(aCase, names));
            }
            case StatementDef.While aWhile -> collectReassigned(aWhile.statement(), names);
            case StatementDef.Synchronized aSynchronized -> collectReassigned(aSynchronized.statement(), names);
            case StatementDef.Try aTry -> {
                collectReassigned(aTry.statement(), names);
                collectReassigned(aTry.finallyStatement(), names);
                aTry.catches().forEach(aCatch -> collectReassigned(aCatch.statement(), names));
            }
            default -> {
            }
        }
    }

    /**
     * The locals of the enclosing body that the lambdas of a statement capture, at any depth of its expressions and
     * of their bodies, where the body assigns the local after declaring it: Java captures only an effectively final
     * local, so the value the bytecode captures where the lambda is created is copied into one.
     *
     * @param statement  The statement
     * @param reassigned Whether the enclosing body reassigns a local of the name
     * @return The captured locals by name, with their types
     */
    static Map<String, TypeDef> capturedReassignedLocals(StatementDef statement, Predicate<String> reassigned) {
        if (statement instanceof ExpressionDef expression) {
            return capturedReassignedLocals(expression, reassigned);
        }
        List<Lambda> lambdas = new ArrayList<>();
        statement.nestedExpressionsStream().forEach(expression -> collectLambdas(expression, lambdas));
        return capturedReassignedLocals(lambdas, reassigned);
    }

    /**
     * The locals of the enclosing body the lambdas of an expression capture - the expression itself, or one at any
     * depth of it - where the body assigns the local after declaring it.
     *
     * @param expression The expression
     * @param reassigned Whether the enclosing body reassigns a local of the name
     * @return The captured locals by name, with their types
     */
    static Map<String, TypeDef> capturedReassignedLocals(ExpressionDef expression, Predicate<String> reassigned) {
        List<Lambda> lambdas = new ArrayList<>();
        collectLambdas(expression, lambdas);
        return capturedReassignedLocals(lambdas, reassigned);
    }

    private static Map<String, TypeDef> capturedReassignedLocals(List<Lambda> lambdas, Predicate<String> reassigned) {
        Map<String, TypeDef> captured = new LinkedHashMap<>();
        for (Lambda lambda : lambdas) {
            Map<String, TypeDef> locals = new LinkedHashMap<>();
            lambda.implementation().getStatements().forEach(body -> collectLocals(body, locals));
            locals.forEach((name, type) -> {
                if (reassigned.test(name)
                    && lambda.implementation().getStatements().stream().noneMatch(body -> declaresLocal(body, name))) {
                    captured.putIfAbsent(name, type);
                }
            });
        }
        return captured;
    }

    /**
     * The lambdas of an expression, at any depth, but not those within the body of another: the enclosing lambda
     * captures what the nested one reads.
     */
    private static void collectLambdas(@Nullable ExpressionDef expression, List<Lambda> lambdas) {
        if (expression == null) {
            // Rejected where the statement is rendered
            return;
        }
        if (expression instanceof Lambda lambda) {
            lambdas.add(lambda);
            return;
        }
        if (expression instanceof ExpressionDef.SwitchYieldCase yieldCase) {
            yieldCase.statement().nestedExpressionsStream().forEach(nested -> collectLambdas(nested, lambdas));
            return;
        }
        expression.nestedExpressionsStream().forEach(nested -> collectLambdas(nested, lambdas));
    }

    /**
     * The locals a statement reads or assigns at any depth, in the bodies of its lambdas too.
     */
    private static void collectLocals(@Nullable StatementDef statement, Map<String, TypeDef> locals) {
        switch (statement) {
            case null -> {
            }
            case StatementDef.Assign assign -> {
                locals.putIfAbsent(assign.variable().name(), assign.variable().type());
                collectLocals(assign.expression(), locals);
            }
            case StatementDef.DefineAndAssign define -> collectLocals(define.expression(), locals);
            case StatementDef.Multi multi -> multi.statements().forEach(child -> collectLocals(child, locals));
            case StatementDef.If anIf -> {
                collectLocals(anIf.condition(), locals);
                collectLocals(anIf.statement(), locals);
            }
            case StatementDef.IfElse ifElse -> {
                collectLocals(ifElse.condition(), locals);
                collectLocals(ifElse.statement(), locals);
                collectLocals(ifElse.elseStatement(), locals);
            }
            case StatementDef.Switch aSwitch -> {
                collectLocals(aSwitch.expression(), locals);
                collectLocals(aSwitch.defaultCase(), locals);
                aSwitch.cases().values().forEach(aCase -> collectLocals(aCase, locals));
            }
            case StatementDef.While aWhile -> {
                collectLocals(aWhile.expression(), locals);
                collectLocals(aWhile.statement(), locals);
            }
            case StatementDef.Synchronized aSynchronized -> {
                collectLocals(aSynchronized.monitor(), locals);
                collectLocals(aSynchronized.statement(), locals);
            }
            case StatementDef.Try aTry -> {
                collectLocals(aTry.statement(), locals);
                collectLocals(aTry.finallyStatement(), locals);
                aTry.catches().forEach(aCatch -> collectLocals(aCatch.statement(), locals));
            }
            case ExpressionDef expression -> collectLocals(expression, locals);
            default -> statement.nestedExpressionsStream().forEach(expression -> collectLocals(expression, locals));
        }
    }

    private static void collectLocals(@Nullable ExpressionDef expression, Map<String, TypeDef> locals) {
        switch (expression) {
            case null -> {
            }
            case VariableDef.Local local -> locals.putIfAbsent(local.name(), local.type());
            case Lambda lambda -> lambda.implementation().getStatements().forEach(body -> collectLocals(body, locals));
            case ExpressionDef.SwitchYieldCase yieldCase -> collectLocals(yieldCase.statement(), locals);
            default -> expression.nestedExpressionsStream().forEach(nested -> collectLocals(nested, locals));
        }
    }

    /**
     * The catches of a try that are written: not one of an exception caught by an earlier catch, nor one of a
     * checked exception the body cannot throw, which the verifier accepts as dead handlers and javac rejects. A
     * dead handler is never reached, so dropping it keeps the behaviour of the bytecode.
     *
     * @param aTry      The try
     * @param objectDef The definition being written, or {@code null}
     * @return The catches to write, in their order
     */
    static List<StatementDef.Try.Catch> liveCatches(StatementDef.Try aTry, @Nullable ObjectDef objectDef) {
        List<StatementDef.Try.Catch> live = new ArrayList<>();
        List<TypeDef> thrown = null;
        boolean thrownKnown = false;
        for (StatementDef.Try.Catch aCatch : aTry.catches()) {
            if (live.stream().anyMatch(earlier -> isSubtype(aCatch.exception(), earlier.exception()))) {
                continue;
            }
            if (isChecked(aCatch.exception())) {
                if (!thrownKnown) {
                    thrown = thrownBy(aTry.statement(), objectDef);
                    thrownKnown = true;
                }
                // Anything can be thrown where an invocation cannot be resolved
                if (thrown != null && thrown.stream().noneMatch(type -> type instanceof ClassTypeDef thrownClass
                    && (isSubtype(thrownClass, aCatch.exception()) || isSubtype(aCatch.exception(), thrownClass)))) {
                    continue;
                }
            }
            live.add(aCatch);
        }
        return live;
    }

    /**
     * Whether an exception is checked: not a `RuntimeException` or an `Error`, nor `Exception` or `Throwable`
     * themselves, which javac lets a catch name whatever the body throws. An exception that cannot be loaded is
     * taken as unchecked, which keeps its catch.
     */
    private static boolean isChecked(ClassTypeDef exception) {
        Class<?> loaded = ClassUtils.forName(exception.getName(), JavaSourceRules.class.getClassLoader()).orElse(null);
        return loaded != null && loaded != Exception.class && loaded != Throwable.class
            && !RuntimeException.class.isAssignableFrom(loaded) && !Error.class.isAssignableFrom(loaded);
    }

    private static boolean isSubtype(ClassTypeDef type, ClassTypeDef supertype) {
        return type.getName().equals(supertype.getName())
            || TypeHierarchy.inherits(type, supertype.getName(), JavaExpressionRules.elementLookup());
    }

    /**
     * The exceptions a statement can throw: those of its throw statements and those the methods and constructors it
     * invokes declare, at any depth - but not in the body of a lambda, which throws when it is called.
     *
     * @return The exception types, or {@code null} where an invocation cannot be resolved, which can throw anything
     */
    @Nullable
    private static List<TypeDef> thrownBy(@Nullable StatementDef statement, @Nullable ObjectDef objectDef) {
        List<TypeDef> thrown = new ArrayList<>();
        return collectThrown(statement, objectDef, thrown) ? thrown : null;
    }

    private static boolean collectThrown(@Nullable StatementDef statement, @Nullable ObjectDef objectDef, List<TypeDef> thrown) {
        switch (statement) {
            case null -> {
                return true;
            }
            case StatementDef.Throw aThrow -> {
                TypeDef type = TypeHierarchy.unwrap(aThrow.expression().type());
                if (!(type instanceof ClassTypeDef)) {
                    return false;
                }
                thrown.add(type);
                return collectThrown(aThrow.expression(), objectDef, thrown);
            }
            case StatementDef.Try aTry -> {
                // What the body throws past the catches, and what the catches and the finally throw
                List<TypeDef> body = thrownBy(aTry.statement(), objectDef);
                if (body == null) {
                    return false;
                }
                body.stream().filter(type -> aTry.catches().stream().noneMatch(aCatch -> type instanceof ClassTypeDef thrownClass
                    && isSubtype(thrownClass, aCatch.exception()))).forEach(thrown::add);
                return aTry.catches().stream().allMatch(aCatch -> collectThrown(aCatch.statement(), objectDef, thrown))
                    && collectThrown(aTry.finallyStatement(), objectDef, thrown);
            }
            case StatementDef.InvokeSuperConstructor invocation -> {
                return collectThrown(invocation.method(), JavaExpressionRules.ownerOf(objectDef, invocation.superInstance().type()),
                    objectDef, thrown) && invocation.values().stream().allMatch(value -> collectThrown(value, objectDef, thrown));
            }
            case StatementDef.Multi multi -> {
                return multi.statements().stream().allMatch(child -> collectThrown(child, objectDef, thrown));
            }
            case StatementDef.If anIf -> {
                return collectThrown(anIf.condition(), objectDef, thrown) && collectThrown(anIf.statement(), objectDef, thrown);
            }
            case StatementDef.IfElse ifElse -> {
                return collectThrown(ifElse.condition(), objectDef, thrown) && collectThrown(ifElse.statement(), objectDef, thrown)
                    && collectThrown(ifElse.elseStatement(), objectDef, thrown);
            }
            case StatementDef.Switch aSwitch -> {
                return collectThrown(aSwitch.expression(), objectDef, thrown) && collectThrown(aSwitch.defaultCase(), objectDef, thrown)
                    && aSwitch.cases().values().stream().allMatch(aCase -> collectThrown(aCase, objectDef, thrown));
            }
            case StatementDef.While aWhile -> {
                return collectThrown(aWhile.expression(), objectDef, thrown) && collectThrown(aWhile.statement(), objectDef, thrown);
            }
            case StatementDef.Synchronized aSynchronized -> {
                return collectThrown(aSynchronized.monitor(), objectDef, thrown) && collectThrown(aSynchronized.statement(), objectDef, thrown);
            }
            case ExpressionDef expression -> {
                return collectThrown(expression, objectDef, thrown);
            }
            default -> {
                return statement.nestedExpressionsStream().allMatch(expression -> collectThrown(expression, objectDef, thrown));
            }
        }
    }

    private static boolean collectThrown(@Nullable ExpressionDef expression, @Nullable ObjectDef objectDef, List<TypeDef> thrown) {
        if (expression == null) {
            // Rejected where the expression is rendered
            return true;
        }
        boolean resolved = switch (expression) {
            // A lambda throws when it is called, not where it is created
            case Lambda _ -> true;
            case ExpressionDef.SwitchYieldCase yieldCase -> collectThrown(yieldCase.statement(), objectDef, thrown);
            case ExpressionDef.InvokeInstanceMethod invocation -> collectThrown(invocation.method(),
                JavaExpressionRules.ownerOf(objectDef, null, invocation.instance().type(), invocation.method().getName(),
                    invocation.method().getParameters().stream().map(ParameterDef::getType).toList()), objectDef, thrown);
            case ExpressionDef.InvokeStaticMethod invocation -> collectThrown(invocation.method(), invocation.classDef(), objectDef, thrown);
            case ExpressionDef.NewInstance newInstance -> collectThrown(
                MethodDef.constructor().addParameters(newInstance.parameterTypes()).build(), newInstance.type(), objectDef, thrown);
            case ExpressionDef.GetPropertyValue property -> collectThrown(JavaIdioms.getPropertyValue(property), objectDef, thrown);
            default -> true;
        };
        return resolved && expression.nestedExpressionsStream().allMatch(nested -> collectThrown(nested, objectDef, thrown));
    }

    /**
     * The exceptions an invoked method declares: those of the model, and those of the method as its owner declares it
     * - a generated one, or a compiled one read through reflection or the compiler.
     */
    private static boolean collectThrown(MethodDef method, @Nullable ClassTypeDef owner, @Nullable ObjectDef objectDef, List<TypeDef> thrown) {
        ObjectDef definition = OverrideResolver.definitionOf(owner, objectDef);
        List<TypeDef> declared = definition != null
            ? declaredThrowTypes(definition, method, new HashSet<>())
            : owner == null ? null : InvokedSignature.thrownTypes(owner, method.getName(),
                method.getParameters().stream().map(ParameterDef::getType).toList(), JavaPoetNames.context());
        if (declared == null) {
            return false;
        }
        for (TypeDef type : declared) {
            TypeDef unwrapped = TypeHierarchy.unwrap(type);
            if (!(unwrapped instanceof ClassTypeDef)) {
                // A variable, `throws E`, can be any exception
                return false;
            }
            thrown.add(unwrapped);
        }
        return true;
    }

    /**
     * The exceptions a generated definition, or the supertype it inherits the method from, declares for a method.
     *
     * @return The exceptions, or {@code null} where no definition or compiled supertype declares the method
     */
    @Nullable
    private static List<TypeDef> declaredThrowTypes(ObjectDef definition, MethodDef method, Set<String> visited) {
        if (!visited.add(definition.getName())) {
            return null;
        }
        List<String> erasures = method.getParameters().stream().map(parameter -> TypeHierarchy.erasedName(parameter.getType())).toList();
        for (MethodDef candidate : definition.getMethods()) {
            if (candidate.getName().equals(method.getName())
                && candidate.getParameters().stream().map(parameter -> TypeHierarchy.erasedName(parameter.getType(), definition)).toList().equals(erasures)) {
                return candidate.getThrowTypes();
            }
        }
        if (method.isConstructor()) {
            // A default constructor throws nothing
            return definition.getMethods().stream().noneMatch(MethodDef::isConstructor) && erasures.isEmpty() ? List.of() : null;
        }
        for (TypeDef supertype : TypeHierarchy.superTypesOf(definition)) {
            if (!(TypeHierarchy.unwrap(supertype) instanceof ClassTypeDef classType)) {
                continue;
            }
            ObjectDef generated = OverrideResolver.definitionOf(classType, null);
            List<TypeDef> found = generated != null
                ? declaredThrowTypes(generated, method, visited)
                : InvokedSignature.thrownTypes(classType, method.getName(),
                    method.getParameters().stream().map(ParameterDef::getType).toList(), JavaPoetNames.context());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * How a statement assigns one field.
     *
     * @param definite   Whether every path that completes it normally has assigned the field
     * @param possible   Whether some path has
     * @param repeatable Whether a path could assign the field a second time, which Java rejects for a blank final
     */
    private record Assignment(boolean definite, boolean possible, boolean repeatable) {
        private static final Assignment NONE = new Assignment(false, false, false);
        private static final Assignment ONCE = new Assignment(true, true, false);
        private static final Assignment VACUOUS = new Assignment(true, false, false);
    }
}
