/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.bytecode.expression.ExpressionWriter;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.JavaIdioms;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

/**
 * The common condition writer methods.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
@Internal
public abstract class AbstractConditionalWriter {

    protected static void pushElseConditionalExpression(GeneratorAdapter generatorAdapter,
                                                        MethodContext context,
                                                        ExpressionDef expressionDef,
                                                        Label elseLabel) {
        if (expressionDef instanceof ExpressionDef.ConditionExpressionDef conditionExpressionDef) {
            pushElseCondition(generatorAdapter, context, conditionExpressionDef, elseLabel);
            return;
        }
        if (!expressionDef.type().equals(TypeDef.Primitive.BOOLEAN) && !expressionDef.type().equals(TypeDef.Primitive.BOOLEAN.wrapperType())) {
            throw new IllegalStateException("Conditional expression should produce a boolean: " + expressionDef);
        }
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, expressionDef, TypeDef.Primitive.BOOLEAN);
        generatorAdapter.push(true);
        generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, elseLabel);
    }

    private static void pushIfConditionalExpression(GeneratorAdapter generatorAdapter,
                                                    MethodContext context,
                                                    ExpressionDef expressionDef,
                                                    Label ifLabel) {
        if (expressionDef instanceof ExpressionDef.ConditionExpressionDef conditionExpressionDef) {
            pushIfCondition(generatorAdapter, context, conditionExpressionDef, ifLabel);
            return;
        }
        if (!expressionDef.type().equals(TypeDef.Primitive.BOOLEAN) && !expressionDef.type().equals(TypeDef.Primitive.BOOLEAN.wrapperType())) {
            throw new IllegalStateException("Conditional expression should produce a boolean: " + expressionDef);
        }
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, expressionDef, TypeDef.Primitive.BOOLEAN);
        generatorAdapter.push(true);
        generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, ifLabel);
    }

    private static void pushEqualsStructurally(GeneratorAdapter generatorAdapter,
                                               MethodContext context,
                                               ExpressionDef left,
                                               ExpressionDef right,
                                               Label ifLabel,
                                               int op) {
        TypeDef leftType = left.type();
        TypeDef rightType = right.type();
        if (leftType.isPrimitive()) {
            pushEqualsReferentially(generatorAdapter, context, left, right.cast(leftType), ifLabel, op);
            return;
        }
        if (rightType.isPrimitive()) {
            pushEqualsReferentially(generatorAdapter, context, left.cast(rightType), right, ifLabel, op);
            return;
        }
        ExpressionWriter.writeExpressionCheckCast(generatorAdapter, context, JavaIdioms.equalsStructurally(left, right), TypeDef.Primitive.BOOLEAN);
        generatorAdapter.push(true);
        generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, op, ifLabel);
    }

    private static void pushEqualsReferentially(GeneratorAdapter generatorAdapter,
                                                MethodContext context,
                                                ExpressionDef left,
                                                ExpressionDef right,
                                                Label label,
                                                int op) {
        TypeDef leftType = left.type();
        ExpressionWriter.writeExpression(generatorAdapter, context, left);
        TypeDef rightType = right.type();
        ExpressionWriter.writeExpression(generatorAdapter, context, right);
        if (leftType instanceof TypeDef.Primitive p1 && rightType instanceof TypeDef.Primitive) {
            generatorAdapter.ifCmp(TypeUtils.getType(p1), op, label);
        } else {
            generatorAdapter.ifCmp(TypeUtils.OBJECT_TYPE, op, label);
        }
    }

    /**
     * Jumps on the outcome of a comparison, or on its opposite. A float or double is compared as javac compares it:
     * {@code <} and {@code <=} through {@code cmpg}, {@code >} and {@code >=} through {@code cmpl}, so that a NaN
     * makes the ordered test false whichever way the jump goes - picking the instruction for the negated jump, as
     * {@link GeneratorAdapter#ifCmp} does, would make {@code NaN < 3} true.
     */
    private static void pushComparison(GeneratorAdapter generatorAdapter,
                                       MethodContext context,
                                       ExpressionDef.ComparisonOperation comparison,
                                       boolean negated,
                                       Label label) {
        ExpressionWriter.writeExpression(generatorAdapter, context, comparison.left());
        ExpressionWriter.writeExpression(generatorAdapter, context, comparison.right());
        Type conditionType = TypeUtils.getType(comparison.left().type(), context.objectDef());
        int jump = negated ? getInvertConditionOp(comparison.opType()) : getConditionOp(comparison.opType());
        int sort = conditionType.getSort();
        if (sort != Type.FLOAT && sort != Type.DOUBLE) {
            generatorAdapter.ifCmp(conditionType, jump, label);
            return;
        }
        boolean cmpg = switch (comparison.opType()) {
            case LESS_THAN, LESS_THAN_OR_EQUAL -> true;
            case EQUAL_TO, NOT_EQUAL_TO, GREATER_THAN, GREATER_THAN_OR_EQUAL -> false;
        };
        if (sort == Type.DOUBLE) {
            generatorAdapter.visitInsn(cmpg ? Opcodes.DCMPG : Opcodes.DCMPL);
        } else {
            generatorAdapter.visitInsn(cmpg ? Opcodes.FCMPG : Opcodes.FCMPL);
        }
        // The modes of the adapter are the opcodes of the jumps against zero
        generatorAdapter.visitJumpInsn(jump, label);
    }

    private static int getInvertConditionOp(ExpressionDef.ComparisonOperation.OpType op) {
        return switch (op) {
            case EQUAL_TO -> GeneratorAdapter.NE;
            case NOT_EQUAL_TO -> GeneratorAdapter.EQ;
            case GREATER_THAN -> GeneratorAdapter.LE;
            case LESS_THAN -> GeneratorAdapter.GE;
            case GREATER_THAN_OR_EQUAL -> GeneratorAdapter.LT;
            case LESS_THAN_OR_EQUAL -> GeneratorAdapter.GT;
        };
    }

    private static int getConditionOp(ExpressionDef.ComparisonOperation.OpType op) {
        return switch (op) {
            case EQUAL_TO -> GeneratorAdapter.EQ;
            case NOT_EQUAL_TO -> GeneratorAdapter.NE;
            case GREATER_THAN -> GeneratorAdapter.GT;
            case LESS_THAN -> GeneratorAdapter.LT;
            case GREATER_THAN_OR_EQUAL -> GeneratorAdapter.GE;
            case LESS_THAN_OR_EQUAL -> GeneratorAdapter.LE;
        };
    }

    private static void pushElseCondition(GeneratorAdapter generatorAdapter,
                                          MethodContext context,
                                          ExpressionDef.ConditionExpressionDef conditionExpressionDef,
                                          Label elseLabel) {
        switch (conditionExpressionDef) {
            case ExpressionDef.InstanceOf instanceOf -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, instanceOf.expression());
                generatorAdapter.instanceOf(TypeUtils.getType(instanceOf.instanceType(), context.objectDef()));
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, elseLabel);
            }
            case ExpressionDef.And andExpressionDef -> {
                pushElseConditionalExpression(generatorAdapter, context, andExpressionDef.left(), elseLabel);
                pushElseConditionalExpression(generatorAdapter, context, andExpressionDef.right(), elseLabel);
            }
            case ExpressionDef.Or orExpressionDef -> {
                Label ifLabel = new Label();
                pushIfConditionalExpression(generatorAdapter, context, orExpressionDef.left(), ifLabel);
                pushIfConditionalExpression(generatorAdapter, context, orExpressionDef.right(), ifLabel);
                generatorAdapter.goTo(elseLabel);
                generatorAdapter.visitLabel(ifLabel);
            }
            case ExpressionDef.ComparisonOperation comparisonOperation ->
                pushComparison(generatorAdapter, context, comparisonOperation, true, elseLabel);
            case ExpressionDef.IsNull isNull -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, isNull.expression());
                generatorAdapter.ifNonNull(elseLabel);
            }
            case ExpressionDef.IsNotNull isNotNull -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, isNotNull.expression());
                generatorAdapter.ifNull(elseLabel);
            }
            case ExpressionDef.IsTrue isTrue -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, isTrue.expression());
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, elseLabel);
            }
            case ExpressionDef.IsFalse isFalse -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, isFalse.expression());
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, elseLabel);
            }
            case ExpressionDef.EqualsReferentially equalsReferentially -> {
                pushEqualsReferentially(generatorAdapter, context, equalsReferentially.instance(), equalsReferentially.other(), elseLabel, GeneratorAdapter.NE);
            }
            case ExpressionDef.EqualsStructurally equalsStructurally -> {
                pushEqualsStructurally(generatorAdapter, context, equalsStructurally.instance(), equalsStructurally.other(), elseLabel, GeneratorAdapter.NE);
            }
            case ExpressionDef.NotEqualsReferentially notEqualsReferentially -> {
                pushEqualsReferentially(generatorAdapter, context, notEqualsReferentially.instance(), notEqualsReferentially.other(), elseLabel, GeneratorAdapter.EQ);
            }
            case ExpressionDef.NotEqualsStructurally notEqualsStructurally -> {
                pushEqualsStructurally(generatorAdapter, context, notEqualsStructurally.instance(), notEqualsStructurally.other(), elseLabel, GeneratorAdapter.EQ);
            }
            default -> throw new UnsupportedOperationException("Unrecognized conditional expression: " + conditionExpressionDef);
        }
    }

    private static void pushIfCondition(GeneratorAdapter generatorAdapter,
                                        MethodContext context,
                                        ExpressionDef.ConditionExpressionDef conditionExpressionDef,
                                        Label ifLabel) {
        switch (conditionExpressionDef) {
            case ExpressionDef.InstanceOf instanceOf -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, instanceOf.expression());
                generatorAdapter.instanceOf(TypeUtils.getType(instanceOf.instanceType(), context.objectDef()));
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, ifLabel);
            }
            case ExpressionDef.And andExpressionDef -> {
                Label elseLabel = new Label();
                pushElseConditionalExpression(generatorAdapter, context, andExpressionDef.left(), elseLabel);
                pushElseConditionalExpression(generatorAdapter, context, andExpressionDef.right(), elseLabel);
                generatorAdapter.goTo(ifLabel);
                generatorAdapter.visitLabel(elseLabel);
            }
            case ExpressionDef.Or orExpressionDef -> {
                pushIfConditionalExpression(generatorAdapter, context, orExpressionDef.left(), ifLabel);
                pushIfConditionalExpression(generatorAdapter, context, orExpressionDef.right(), ifLabel);
            }
            case ExpressionDef.ComparisonOperation comparisonOperation ->
                pushComparison(generatorAdapter, context, comparisonOperation, false, ifLabel);
            case ExpressionDef.IsNull isNull -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, isNull.expression());
                generatorAdapter.ifNull(ifLabel);
            }
            case ExpressionDef.IsNotNull isNotNull -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, isNotNull.expression());
                generatorAdapter.ifNonNull(ifLabel);
            }
            case ExpressionDef.IsTrue isTrue -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, isTrue.expression());
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, ifLabel);
            }
            case ExpressionDef.IsFalse isFalse -> {
                ExpressionWriter.writeExpression(generatorAdapter, context, isFalse.expression());
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, ifLabel);
            }
            case ExpressionDef.EqualsReferentially equalsReferentially -> {
                pushEqualsReferentially(generatorAdapter, context, equalsReferentially.instance(), equalsReferentially.other(), ifLabel, GeneratorAdapter.EQ);
            }
            case ExpressionDef.EqualsStructurally equalsStructurally -> {
                pushEqualsStructurally(generatorAdapter, context, equalsStructurally.instance(), equalsStructurally.other(), ifLabel, GeneratorAdapter.EQ);
            }
            case ExpressionDef.NotEqualsReferentially notEqualsReferentially -> {
                pushEqualsReferentially(generatorAdapter, context, notEqualsReferentially.instance(), notEqualsReferentially.other(), ifLabel, GeneratorAdapter.NE);
            }
            case ExpressionDef.NotEqualsStructurally notEqualsStructurally -> {
                pushEqualsStructurally(generatorAdapter, context, notEqualsStructurally.instance(), notEqualsStructurally.other(), ifLabel, GeneratorAdapter.NE);
            }
            default -> throw new UnsupportedOperationException("Unrecognized conditional expression: " + conditionExpressionDef);
        }
    }
}
