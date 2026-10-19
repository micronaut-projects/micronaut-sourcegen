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
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.Completion;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Lambda;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What Java makes of a statement: whether it can complete normally, whether it has to be written as a statement of
 * its own, and where a blank final static field is definitely assigned.
 *
 * @since 2.3
 */
@Internal
final class JavaSourceRules {

    private final JavaWriteContext context;
    // Whether a statement can complete, as javac decides it: a loop over a constant expression of the value true never
    // completes, which takes Java's constant folding, and a catch javac takes as unreachable does not count
    private final Completion completion;
    // The constant variables being evaluated, one initialized by the other
    private int constantDepth;
    private final Map<ObjectDef, FinalFields> finalFields = new IdentityHashMap<>();

    JavaSourceRules(JavaWriteContext context) {
        this.context = context;
        this.completion = Completion.javaSource(this::isConstantTrue, aTry -> context.exceptions().liveCatches(aTry));
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

    /**
     * Whether a lambda is written with a block body: one of several statements, of a conditional or a switch of void
     * results, of a copy of a captured local it assigns, and of a call throwing a checked exception its functional
     * method does not declare.
     *
     * @param lambda    The lambda
     * @param objectDef The definition being written
     * @return true if its body is a block
     */
    boolean hasBlockBody(Lambda lambda, @Nullable ObjectDef objectDef) {
        ExpressionDef body = singleExpressionBody(lambda);
        if (body == null || JavaStatementRenderer.isVoidBranching(body)) {
            return true;
        }
        List<StatementDef> statements = lambda.implementation().getStatements();
        Set<String> assigned = JavaLambdaRules.assignedLocals(statements);
        if (JavaLambdaRules.capturedLocals(lambda).keySet().stream().anyMatch(assigned::contains)) {
            return true;
        }
        JavaExceptionRules exceptions = context.exceptions();
        List<TypeDef> declared = context.exceptions().functionalThrows(lambda.type());
        return exceptions.thrown(statements, false, objectDef, lambda.implementation()).stream()
            .anyMatch(thrown -> !exceptions.handles(declared, thrown));
    }

    boolean containsBlockBodyLambda(StatementDef statementDef, @Nullable ObjectDef objectDef) {
        return statementDef.nestedExpressionsStream().anyMatch(expression -> containsBlockBodyLambda(expression, objectDef));
    }

    boolean containsBlockBodyLambda(ExpressionDef expressionDef, @Nullable ObjectDef objectDef) {
        if (expressionDef instanceof Lambda lambda) {
            // A lambda does not expose its body as nested expressions, so descend into it explicitly
            return hasBlockBody(lambda, objectDef)
                || lambda.implementation().getStatements().stream().anyMatch(statement -> containsBlockBodyLambda(statement, objectDef));
        }
        return expressionDef.nestedExpressionsStream().anyMatch(expression -> containsBlockBodyLambda(expression, objectDef));
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
    boolean keepsFinal(@Nullable ObjectDef objectDef, String fieldName) {
        return objectDef != null && finalFields(objectDef).statics().contains(fieldName);
    }

    /**
     * Whether a blank final instance field of the definition is definitely assigned exactly once by each constructor
     * that does not delegate to another, and assigned nowhere else, which is what Java requires of one. The bytecode
     * writers leave a field no constructor assigns at its default value, and assign one twice where the model does.
     *
     * @param objectDef The definition
     * @param fieldName The field name
     * @return true if the field keeps its {@code final} modifier
     */
    boolean keepsFinalInstanceField(ObjectDef objectDef, String fieldName) {
        return finalFields(objectDef).instances().contains(fieldName);
    }

    /**
     * The blank final fields of a definition that keep their {@code final} modifier, decided once for the definition.
     */
    private FinalFields finalFields(ObjectDef objectDef) {
        return finalFields.computeIfAbsent(objectDef, JavaSourceRules::finalFieldsOf);
    }

    private static FinalFields finalFieldsOf(ObjectDef objectDef) {
        List<FieldDef> fields = switch (objectDef) {
            case ClassDef classDef -> classDef.getFields();
            case EnumDef enumDef -> enumDef.getFields();
            case AnnotationObjectDef annotationDef -> annotationDef.getFields();
            default -> List.of();
        };
        StatementDef initializer = objectDef instanceof ClassDef classDef ? classDef.getStaticInitializer() : null;
        // A local of the same name takes over the unqualified assignment a static field would need, and a return
        // ends the initializer before the assignments after it
        Set<String> initializerLocals = initializer == null ? Set.of() : declaredLocals(List.of(initializer));
        boolean[] returns = {false};
        if (initializer != null) {
            JavaLambdaRules.forEachStatement(List.of(initializer), statement -> returns[0] |= statement instanceof StatementDef.Return);
        }
        Set<String> statics = new HashSet<>();
        Set<String> instances = new HashSet<>();
        for (FieldDef field : fields) {
            String name = field.getName();
            if (!field.getModifiers().contains(Modifier.FINAL) || field.getInitializer().isPresent()) {
                continue;
            }
            if (!field.getModifiers().contains(Modifier.STATIC)) {
                if (assignedOnceByConstructors(objectDef, name)) {
                    instances.add(name);
                }
            } else if (initializer != null && !returns[0] && !initializerLocals.contains(name)
                && assignedOnceByInitializer(objectDef.asTypeDef().getName(), initializer, name)) {
                statics.add(name);
            }
        }
        return new FinalFields(statics, instances);
    }

    private static boolean assignedOnceByInitializer(String ownerName, StatementDef initializer, String fieldName) {
        // A field of another type shares nothing with this one but its name
        Assignment assignment = assignmentOf(initializer, statement -> statement instanceof StatementDef.PutStaticField put
            && put.field().name().equals(fieldName) && put.field().ownerType().getName().equals(ownerName));
        return assignment.definite() && !assignment.repeatable();
    }

    private static boolean assignedOnceByConstructors(ObjectDef objectDef, String fieldName) {
        Predicate<StatementDef> assigns = statement -> statement instanceof StatementDef.PutField put
            && put.field().name().equals(fieldName) && put.field().instance() instanceof VariableDef.This;
        boolean constructed = false;
        for (MethodDef method : objectDef.getMethods()) {
            List<StatementDef> statements = method.getStatements();
            boolean[] inLambdas = {false};
            JavaLambdaRules.forEachExpression(statements, expression -> {
                if (expression instanceof ExpressionDef.Lambda lambda) {
                    lambda.implementation().getStatements().forEach(statement -> inLambdas[0] |= assignmentOf(statement, assigns).possible());
                }
            });
            Assignment assignment = assignmentOf(new StatementDef.Multi(statements), assigns);
            if (inLambdas[0]) {
                return false;
            }
            if (!method.isConstructor()) {
                if (assignment.possible()) {
                    return false;
                }
                continue;
            }
            constructed = true;
            boolean delegates = !statements.isEmpty() && statements.getFirst() instanceof ExpressionDef.InvokeInstanceMethod invocation
                && invocation.method().isConstructor() && invocation.instance() instanceof VariableDef.This;
            if (delegates ? assignment.possible() : !assignment.definite() || assignment.repeatable()) {
                return false;
            }
        }
        return constructed;
    }

    /**
     * The names of the locals a statement declares, in any of its blocks.
     */
    static Set<String> declaredLocals(List<StatementDef> statements) {
        Set<String> names = new HashSet<>();
        statements.forEach(statement -> collectLocals(statement, names));
        return names;
    }

    private static void collectLocals(@Nullable StatementDef statement, Set<String> names) {
        switch (statement) {
            case StatementDef.DefineAndAssign define -> names.add(define.variable().name());
            case StatementDef.Multi multi -> multi.statements().forEach(child -> collectLocals(child, names));
            case StatementDef.If anIf -> collectLocals(anIf.statement(), names);
            case StatementDef.IfElse ifElse -> {
                collectLocals(ifElse.statement(), names);
                collectLocals(ifElse.elseStatement(), names);
            }
            case StatementDef.Switch aSwitch -> {
                collectLocals(aSwitch.defaultCase(), names);
                aSwitch.cases().values().forEach(aCase -> collectLocals(aCase, names));
            }
            case StatementDef.While aWhile -> collectLocals(aWhile.statement(), names);
            case StatementDef.Synchronized aSynchronized -> collectLocals(aSynchronized.statement(), names);
            case StatementDef.Try aTry -> {
                collectLocals(aTry.statement(), names);
                collectLocals(aTry.finallyStatement(), names);
                aTry.catches().forEach(aCatch -> collectLocals(aCatch.statement(), names));
            }
            case null, default -> {
            }
        }
    }

    private static Assignment assignmentOf(@Nullable StatementDef statement, Predicate<StatementDef> assigns) {
        return switch (statement) {
            case null -> Assignment.NONE;
            // No path completes normally past it, so the field is as assigned as it needs to be there
            case StatementDef.Throw aThrow -> Assignment.VACUOUS;
            // The value is evaluated first: a block of a switch expression in it can assign the field too
            case StatementDef matched when assigns.test(matched) -> sequence(nestedAssignment(matched, assigns), Assignment.ONCE);
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
            default -> nestedAssignment(statement, assigns);
        };
    }

    /**
     * How the blocks of the switch expressions of a statement assign the field: one of them may run, or none.
     */
    private static Assignment nestedAssignment(StatementDef statement, Predicate<StatementDef> assigns) {
        Assignment[] result = {Assignment.NONE};
        JavaLambdaRules.forEachExpression(List.of(statement), expression -> {
            if (expression instanceof ExpressionDef.SwitchYieldCase block) {
                Assignment assignment = assignmentOf(block.statement(), assigns);
                result[0] = sequence(result[0], new Assignment(false, assignment.possible(), assignment.repeatable()));
            }
        });
        return result[0];
    }

    /**
     * How two statements, one after the other, assign the field.
     */
    private static Assignment sequence(Assignment first, Assignment second) {
        return new Assignment(first.definite() || second.definite(), first.possible() || second.possible(),
            first.repeatable() || second.repeatable() || first.possible() && second.possible());
    }

    /**
     * @param statementDef A statement
     * @return Whether javac takes the statement as one that cannot complete normally
     */
    boolean cannotCompleteNormally(StatementDef statementDef) {
        return completion.cannotCompleteNormally(statementDef);
    }

    private boolean isConstantTrue(ExpressionDef expression) {
        return Boolean.TRUE.equals(constantValue(expression));
    }

    /**
     * @param expression A condition
     * @return Whether it is a constant expression of the value false: a loop over it is never entered, and javac
     * rejects its body as unreachable
     */
    boolean isConstantFalse(ExpressionDef expression) {
        return Boolean.FALSE.equals(constantValue(expression));
    }

    /**
     * The value of a constant expression as Java evaluates the source written for one, which is what decides whether
     * a loop over it can complete: a literal, a name of a constant variable, and the operations, comparisons and
     * conditionals of constant expressions.
     */
    @Nullable
    private Object constantValue(ExpressionDef expression) {
        return switch (expression) {
            case ExpressionDef.Constant constant -> constant.value() instanceof Boolean || constant.value() instanceof Number
                || constant.value() instanceof Character || constant.value() instanceof String ? constant.value() : null;
            // As the cast is written: of nested casts only the last one, none that the source drops, and one to a
            // primitive converts a constant expression, where a boxing cast, `(Boolean) true`, makes none
            case ExpressionDef.Cast cast -> {
                ExpressionDef operand = JavaCasts.collapseNestedCasts(cast.expressionDef());
                yield JavaCasts.dropsCast(cast, operand, JavaCasts.CastContext.DEFAULT) ? constantValue(operand)
                    : cast.type() instanceof TypeDef.Primitive primitive ? converted(constantValue(operand), primitive) : null;
            }
            // An operation of bytes or shorts, and a negated char, is written narrowed to its type
            case ExpressionDef.MathBinaryOperation math -> narrowedConstant(math,
                computed(math.opType(), constantValue(math.left()), constantValue(math.right())));
            case ExpressionDef.MathUnaryOperation negate -> narrowedConstant(negate, negated(constantValue(negate.expression())));
            // A condition in a cast is written without it
            case ExpressionDef.IsTrue isTrue -> JavaCasts.unwrapCasts(isTrue.expression()) instanceof ExpressionDef.ConditionExpressionDef condition
                ? constantValue(condition) : constantValue(isTrue.expression());
            case ExpressionDef.IsFalse isFalse -> (JavaCasts.unwrapCasts(isFalse.expression()) instanceof ExpressionDef.ConditionExpressionDef condition
                ? constantValue(condition) : constantValue(isFalse.expression())) instanceof Boolean value ? !value : null;
            // A conditional of a primitive or a String type, of constants
            case ExpressionDef.IfElse conditional when conditional.type().isPrimitive() || TypeDef.STRING.equals(conditional.type()) -> {
                Object then = constantValue(conditional.ifExpression());
                Object otherwise = constantValue(conditional.elseExpression());
                yield constantValue(conditional.condition()) instanceof Boolean condition && then != null && otherwise != null
                    ? (condition ? then : otherwise) : null;
            }
            case ExpressionDef.StringConcatenation concat when TypeDef.STRING.equals(concat.left().type())
                || TypeDef.STRING.equals(concat.right().type()) -> {
                Object left = constantValue(concat.left());
                Object right = constantValue(concat.right());
                yield left != null && right != null ? String.valueOf(left) + right : null;
            }
            // A qualified name of a constant variable
            case VariableDef.StaticField field -> fieldValue(field);
            case ExpressionDef.And and -> constantValue(and.left()) instanceof Boolean left
                && constantValue(and.right()) instanceof Boolean right ? left && right : null;
            case ExpressionDef.Or or -> constantValue(or.left()) instanceof Boolean left
                && constantValue(or.right()) instanceof Boolean right ? left || right : null;
            case ExpressionDef.ComparisonOperation comparison -> compared(comparison.opType(),
                comparison.left(), comparison.right());
            case ExpressionDef.EqualsReferentially equals -> referentiallyCompared(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO,
                equals.instance(), equals.other());
            case ExpressionDef.NotEqualsReferentially notEquals -> referentiallyCompared(ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO,
                notEquals.instance(), notEquals.other());
            // Of a primitive written as `==` of the operands converted to its type
            case ExpressionDef.EqualsStructurally equals -> structurallyCompared(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO,
                equals.instance(), equals.other());
            case ExpressionDef.NotEqualsStructurally notEquals -> structurallyCompared(ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO,
                notEquals.instance(), notEquals.other());
            default -> null;
        };
    }

    @Nullable
    private Object fieldValue(VariableDef.StaticField field) {
        if (constantDepth > 8) {
            // A constant variable initialized by another, and that by the first
            return null;
        }
        constantDepth++;
        try {
            return JavaConstantFields.valueOf(field, this::constantValue, context.scope());
        } finally {
            constantDepth--;
        }
    }

    /**
     * A constant converted to a primitive type as a cast does.
     */
    @Nullable
    private static Object converted(@Nullable Object value, TypeDef.Primitive primitive) {
        if (value instanceof Boolean) {
            return primitive.equals(TypeDef.Primitive.BOOLEAN) ? value : null;
        }
        if (!(value instanceof Number || value instanceof Character) || primitive.equals(TypeDef.Primitive.BOOLEAN)) {
            return null;
        }
        Number number = value instanceof Character c ? Integer.valueOf(c) : (Number) value;
        boolean floating = number instanceof Double || number instanceof Float;
        return switch (primitive.name()) {
            case "byte" -> floating ? (byte) (int) number.doubleValue() : number.byteValue();
            case "short" -> floating ? (short) (int) number.doubleValue() : number.shortValue();
            case "char" -> floating ? (char) (int) number.doubleValue() : (char) number.intValue();
            case "int" -> floating ? (int) number.doubleValue() : number.intValue();
            case "long" -> floating ? (long) number.doubleValue() : number.longValue();
            case "float" -> number.floatValue();
            case "double" -> number.doubleValue();
            default -> null;
        };
    }

    @Nullable
    private static Object narrowedConstant(ExpressionDef operation, @Nullable Object value) {
        TypeDef.Primitive narrowed = JavaTypes.narrowedPrimitive(operation);
        return narrowed != null ? converted(value, narrowed) : value;
    }

    @Nullable
    private static Object negated(@Nullable Object value) {
        return switch (value) {
            case Double d -> -d;
            case Float f -> -f;
            case Long l -> -l;
            case Number n -> -n.intValue();
            case Character c -> -c;
            case null, default -> null;
        };
    }

    /**
     * An arithmetic operation of constants, with binary numeric promotion; a division by zero of integers is no
     * constant, since it throws.
     */
    @Nullable
    private static Object computed(ExpressionDef.MathBinaryOperation.OpType op, @Nullable Object left, @Nullable Object right) {
        if (!(left instanceof Number || left instanceof Character) || !(right instanceof Number || right instanceof Character)) {
            return null;
        }
        if (left instanceof Double || right instanceof Double || left instanceof Float || right instanceof Float) {
            boolean single = !(left instanceof Double || right instanceof Double);
            double l = single ? floatOf(left) : doubleOf(left);
            double r = single ? floatOf(right) : doubleOf(right);
            Double result = switch (op) {
                case ADDITION -> l + r;
                case SUBTRACTION -> l - r;
                case MULTIPLICATION -> l * r;
                case DIVISION -> l / r;
                case MODULUS -> l % r;
                default -> null;
            };
            return result == null ? null : single ? (Object) (float) (double) result : result;
        }
        // A shift has the type of its promoted left operand alone
        boolean shift = op == ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT
            || op == ExpressionDef.MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT
            || op == ExpressionDef.MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT;
        boolean wide = left instanceof Long || !shift && right instanceof Long;
        long l = longOf(left);
        long r = longOf(right);
        if ((op == ExpressionDef.MathBinaryOperation.OpType.DIVISION || op == ExpressionDef.MathBinaryOperation.OpType.MODULUS) && r == 0) {
            return null;
        }
        if (!wide) {
            int a = (int) l;
            int b = (int) r;
            return switch (op) {
                case ADDITION -> a + b;
                case SUBTRACTION -> a - b;
                case MULTIPLICATION -> a * b;
                case DIVISION -> a / b;
                case MODULUS -> a % b;
                case BITWISE_AND -> a & b;
                case BITWISE_OR -> a | b;
                case BITWISE_XOR -> a ^ b;
                case BITWISE_LEFT_SHIFT -> a << b;
                case BITWISE_RIGHT_SHIFT -> a >> b;
                case BITWISE_UNSIGNED_RIGHT_SHIFT -> a >>> b;
            };
        }
        return switch (op) {
            case ADDITION -> l + r;
            case SUBTRACTION -> l - r;
            case MULTIPLICATION -> l * r;
            case DIVISION -> l / r;
            case MODULUS -> l % r;
            case BITWISE_AND -> l & r;
            case BITWISE_OR -> l | r;
            case BITWISE_XOR -> l ^ r;
            case BITWISE_LEFT_SHIFT -> l << r;
            case BITWISE_RIGHT_SHIFT -> l >> r;
            case BITWISE_UNSIGNED_RIGHT_SHIFT -> l >>> r;
        };
    }

    @Nullable
    private Boolean structurallyCompared(ExpressionDef.ComparisonOperation.OpType op, ExpressionDef left, ExpressionDef right) {
        List<ExpressionDef> operands = JavaCasts.structuralOperands(left, right);
        return operands == null ? null : referentiallyCompared(op, operands.get(0), operands.get(1));
    }

    /**
     * A comparison of the constant values of two operands; boxed constants compared as references are no constants.
     */
    @Nullable
    private Boolean compared(ExpressionDef.ComparisonOperation.OpType op, ExpressionDef left, ExpressionDef right) {
        return compared(op, left, right, JavaCasts.CastContext.DEFAULT);
    }

    /**
     * A comparison of references, which the source writes without their casts to {@code Object}.
     */
    @Nullable
    private Boolean referentiallyCompared(ExpressionDef.ComparisonOperation.OpType op, ExpressionDef left, ExpressionDef right) {
        return compared(op, left, right, JavaCasts.arePrimitiveReferenceEqualityOperands(left, right)
            ? JavaCasts.CastContext.PRIMITIVE_EQUALITY : JavaCasts.CastContext.OBJECT_REFERENCE);
    }

    @Nullable
    private Boolean compared(ExpressionDef.ComparisonOperation.OpType op, ExpressionDef left, ExpressionDef right,
                                    JavaCasts.CastContext castContext) {
        boolean equality = op == ExpressionDef.ComparisonOperation.OpType.EQUAL_TO || op == ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO;
        if (equality && (JavaCasts.comparesBoxed(left, right) || JavaCasts.comparesBoxed(right, left))) {
            return null;
        }
        return compared(op, constantValue(JavaCasts.writtenNode(left, castContext)),
            constantValue(JavaCasts.writtenNode(right, castContext)));
    }

    @Nullable
    private static Boolean compared(ExpressionDef.ComparisonOperation.OpType op, @Nullable Object left, @Nullable Object right) {
        if (left instanceof String && right instanceof String) {
            // String constants are interned: `==` compares them as `equals` does
            return switch (op) {
                case EQUAL_TO -> left.equals(right);
                case NOT_EQUAL_TO -> !left.equals(right);
                default -> null;
            };
        }
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
        // Binary numeric promotion, and the operators themselves: `-0.0 == 0.0`, a NaN is unordered, and a long keeps
        // every digit
        if (left instanceof Double || right instanceof Double) {
            return comparedDoubles(op, doubleOf(left), doubleOf(right));
        }
        if (left instanceof Float || right instanceof Float) {
            return comparedDoubles(op, floatOf(left), floatOf(right));
        }
        long l = longOf(left);
        long r = longOf(right);
        return switch (op) {
            case EQUAL_TO -> l == r;
            case NOT_EQUAL_TO -> l != r;
            case GREATER_THAN -> l > r;
            case LESS_THAN -> l < r;
            case GREATER_THAN_OR_EQUAL -> l >= r;
            case LESS_THAN_OR_EQUAL -> l <= r;
        };
    }

    private static boolean comparedDoubles(ExpressionDef.ComparisonOperation.OpType op, double l, double r) {
        return switch (op) {
            case EQUAL_TO -> l == r;
            case NOT_EQUAL_TO -> l != r;
            case GREATER_THAN -> l > r;
            case LESS_THAN -> l < r;
            case GREATER_THAN_OR_EQUAL -> l >= r;
            case LESS_THAN_OR_EQUAL -> l <= r;
        };
    }

    private static double doubleOf(Object value) {
        return value instanceof Character c ? c : ((Number) value).doubleValue();
    }

    private static float floatOf(Object value) {
        return value instanceof Character c ? c : ((Number) value).floatValue();
    }

    private static long longOf(Object value) {
        return value instanceof Character c ? c : ((Number) value).longValue();
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

    /**
     * The blank final fields of a definition that keep their {@code final} modifier.
     *
     * @param statics   The names of the static ones
     * @param instances The names of the instance ones
     */
    private record FinalFields(Set<String> statics, Set<String> instances) {
    }
}
