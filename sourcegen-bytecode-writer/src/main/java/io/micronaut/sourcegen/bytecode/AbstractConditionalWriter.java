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
            if (expressionDef instanceof ExpressionDef.InstanceOf instanceOf) {
                ExpressionWriter.writeExpression(generatorAdapter, context, instanceOf.expression());
                generatorAdapter.instanceOf(TypeUtils.getType(instanceOf.instanceType(), context.objectDef()));
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, elseLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.And andExpressionDef) {
                pushElseConditionalExpression(generatorAdapter, context, andExpressionDef.left(), elseLabel);
                pushElseConditionalExpression(generatorAdapter, context, andExpressionDef.right(), elseLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.Or orExpressionDef) {
                Label ifLabel = new Label();
                pushIfConditionalExpression(generatorAdapter, context, orExpressionDef.left(), ifLabel);
                pushIfConditionalExpression(generatorAdapter, context, orExpressionDef.right(), ifLabel);
                generatorAdapter.goTo(elseLabel);
                generatorAdapter.visitLabel(ifLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.ComparisonOperation comparisonOperation) {
                ExpressionWriter.writeExpression(generatorAdapter, context, comparisonOperation.left());
                ExpressionWriter.writeExpression(generatorAdapter, context, comparisonOperation.right());
                Type conditionType = TypeUtils.getType(comparisonOperation.left().type(), context.objectDef());
                generatorAdapter.ifCmp(conditionType, getInvertConditionOp(comparisonOperation.opType()), elseLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.IsNull isNull) {
                ExpressionWriter.writeExpression(generatorAdapter, context, isNull.expression());
                generatorAdapter.ifNonNull(elseLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.IsNotNull isNotNull) {
                ExpressionWriter.writeExpression(generatorAdapter, context, isNotNull.expression());
                generatorAdapter.ifNull(elseLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.IsTrue isTrue) {
                ExpressionWriter.writeExpression(generatorAdapter, context, isTrue.expression());
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, elseLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.IsFalse isFalse) {
                ExpressionWriter.writeExpression(generatorAdapter, context, isFalse.expression());
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, elseLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.EqualsReferentially equalsReferentially) {
                pushEqualsReferentially(generatorAdapter, context, equalsReferentially.instance(), equalsReferentially.other(), elseLabel, GeneratorAdapter.NE);
                return;
            }
            if (expressionDef instanceof ExpressionDef.EqualsStructurally equalsStructurally) {
                pushEqualsStructurally(generatorAdapter, context, equalsStructurally.instance(), equalsStructurally.other(), elseLabel, GeneratorAdapter.NE);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.NotEqualsReferentially notEqualsReferentially) {
                pushEqualsReferentially(generatorAdapter, context, notEqualsReferentially.instance(), notEqualsReferentially.other(), elseLabel, GeneratorAdapter.EQ);
                return;
            }
            if (expressionDef instanceof ExpressionDef.NotEqualsStructurally notEqualsStructurally) {
                pushEqualsStructurally(generatorAdapter, context, notEqualsStructurally.instance(), notEqualsStructurally.other(), elseLabel, GeneratorAdapter.EQ);
                return;
            }
            throw new UnsupportedOperationException("Unrecognized conditional expression: " + conditionExpressionDef);
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
            if (expressionDef instanceof ExpressionDef.InstanceOf instanceOf) {
                ExpressionWriter.writeExpression(generatorAdapter, context, instanceOf.expression());
                generatorAdapter.instanceOf(TypeUtils.getType(instanceOf.instanceType(), context.objectDef()));
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, ifLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.And andExpressionDef) {
                Label elseLabel = new Label();
                pushElseConditionalExpression(generatorAdapter, context, andExpressionDef.left(), elseLabel);
                pushElseConditionalExpression(generatorAdapter, context, andExpressionDef.right(), elseLabel);
                generatorAdapter.goTo(ifLabel);
                generatorAdapter.visitLabel(elseLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.Or orExpressionDef) {
                pushIfConditionalExpression(generatorAdapter, context, orExpressionDef.left(), ifLabel);
                pushIfConditionalExpression(generatorAdapter, context, orExpressionDef.right(), ifLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.ComparisonOperation comparisonOperation) {
                ExpressionWriter.writeExpression(generatorAdapter, context, comparisonOperation.left());
                ExpressionWriter.writeExpression(generatorAdapter, context, comparisonOperation.right());
                Type conditionType = TypeUtils.getType(comparisonOperation.left().type(), context.objectDef());
                generatorAdapter.ifCmp(conditionType, getConditionOp(comparisonOperation.opType()), ifLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.IsNull isNull) {
                ExpressionWriter.writeExpression(generatorAdapter, context, isNull.expression());
                generatorAdapter.ifNull(ifLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.IsNotNull isNotNull) {
                ExpressionWriter.writeExpression(generatorAdapter, context, isNotNull.expression());
                generatorAdapter.ifNonNull(ifLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.IsTrue isTrue) {
                ExpressionWriter.writeExpression(generatorAdapter, context, isTrue.expression());
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, ifLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.IsFalse isFalse) {
                ExpressionWriter.writeExpression(generatorAdapter, context, isFalse.expression());
                generatorAdapter.push(true);
                generatorAdapter.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.NE, ifLabel);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.EqualsReferentially equalsReferentially) {
                pushEqualsReferentially(generatorAdapter, context, equalsReferentially.instance(), equalsReferentially.other(), ifLabel, GeneratorAdapter.EQ);
                return;
            }
            if (expressionDef instanceof ExpressionDef.EqualsStructurally equalsStructurally) {
                pushEqualsStructurally(generatorAdapter, context, equalsStructurally.instance(), equalsStructurally.other(), ifLabel, GeneratorAdapter.EQ);
                return;
            }
            if (conditionExpressionDef instanceof ExpressionDef.NotEqualsReferentially notEqualsReferentially) {
                pushEqualsReferentially(generatorAdapter, context, notEqualsReferentially.instance(), notEqualsReferentially.other(), ifLabel, GeneratorAdapter.NE);
                return;
            }
            if (expressionDef instanceof ExpressionDef.NotEqualsStructurally notEqualsStructurally) {
                pushEqualsStructurally(generatorAdapter, context, notEqualsStructurally.instance(), notEqualsStructurally.other(), ifLabel, GeneratorAdapter.NE);
                return;
            }
            throw new UnsupportedOperationException("Unrecognized conditional expression: " + conditionExpressionDef);
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

}
