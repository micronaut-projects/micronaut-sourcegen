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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The checks the model runs when an expression or a statement is constructed, rejecting the shapes no backend can
 * write: a Java or Kotlin source would not compile, and the bytecode would not verify or would compute something else.
 *
 * <p>The checks decide only from what the model knows for certain. A type variable, a wildcard, or a class the model
 * knows by its name only is never rejected: it may turn out to be anything. The messages name the operation, the
 * types of its operands and what it expects.</p>
 *
 * @author Denis Stepanov
 * @since 2.3
 */
@Internal
final class ModelChecks {

    private static final Set<String> NUMERIC_WRAPPERS = Set.of(
        Byte.class.getName(), Short.class.getName(), Integer.class.getName(), Long.class.getName(),
        Float.class.getName(), Double.class.getName(), Character.class.getName()
    );

    private ModelChecks() {
    }

    /**
     * Rejects an operand that produces no value.
     *
     * @param operation The operation
     * @param role      What the operand is to the operation
     * @param operand   The operand
     */
    static void requireValue(String operation, String role, ExpressionDef operand) {
        if (kind(operand.type()) == Kind.VOID) {
            throw fail(operation, role + " " + describe(operand.type()), "a value, not void");
        }
    }

    /**
     * Rejects operands that produce no value.
     *
     * @param operation The operation
     * @param role      What the operands are to the operation
     * @param operands  The operands
     */
    static void requireValues(String operation, String role, List<? extends ExpressionDef> operands) {
        for (int i = 0; i < operands.size(); i++) {
            requireValue(operation, role + " " + i, operands.get(i));
        }
    }

    /**
     * Rejects a condition that is certainly not a {@code boolean}.
     *
     * @param operation The operation
     * @param condition The condition
     */
    static void requireCondition(String operation, ExpressionDef condition) {
        Kind kind = kind(condition.type());
        if (kind == Kind.VOID || kind == Kind.NUMERIC || kind == Kind.STRING || kind == Kind.ARRAY) {
            throw fail(operation, "condition " + describe(condition.type()), "a boolean or a Boolean");
        }
    }

    /**
     * Rejects a cast of no value to a type, and a cast between a boolean and a number. A cast of a primitive to or
     * from any reference is kept: the backends box, or unbox through {@code Number}, and check the cast.
     *
     * @param target  The type cast to
     * @param operand The expression cast
     */
    static void requireCastable(TypeDef target, ExpressionDef operand) {
        if (kind(target) == Kind.VOID) {
            // Discards the value, if any
            return;
        }
        requireValue("Cast to " + describe(target), "operand", operand);
        TypeDef source = operand.type();
        if (!isPrimitiveValue(source) && !isPrimitiveValue(target)) {
            return;
        }
        if (!convertible(kind(source), kind(target))) {
            throw fail("Cast", describe(source) + " to " + describe(target),
                "a conversion between a boolean and a boolean, or between numbers and characters");
        }
    }

    /**
     * Rejects a value stored where it cannot be converted to: no value, {@code null} in a primitive, a boolean in a
     * number and a number in a boolean.
     *
     * @param operation The operation
     * @param target    The type of the variable, the field or the array element
     * @param value     The value
     */
    static void requireAssignable(String operation, TypeDef target, ExpressionDef value) {
        requireValue(operation, "value", value);
        if (kind(target) == Kind.VOID) {
            throw fail(operation, "target " + describe(target), "a variable of a type other than void");
        }
        TypeDef source = value.type();
        if (!isPrimitiveValue(source) && !isPrimitiveValue(target)) {
            return;
        }
        if (isNullConstant(value)) {
            if (isPrimitiveValue(target)) {
                throw fail(operation, "null to " + describe(target), "a value of " + describe(target) + ", not null");
            }
            return;
        }
        if (!convertible(kind(source), kind(target))) {
            throw fail(operation, describe(source) + " to " + describe(target), "a value convertible to " + describe(target));
        }
    }

    /**
     * Rejects an equality of a boolean and a number that the backends cannot agree on: javac rejects it, and the
     * bytecode writers convert one operand to the type of the other, which no cast between a boolean and a number
     * does. Compared by reference, a boolean and a number that are not both primitive are boxed, which are distinct.
     *
     * @param operation   The operation
     * @param instance    The left operand
     * @param other       The right operand
     * @param referential Whether the operands are compared by reference
     */
    static void requireComparable(String operation, ExpressionDef instance, ExpressionDef other, boolean referential) {
        Kind left = kind(instance.type());
        Kind right = kind(other.type());
        boolean mixed = left == Kind.BOOLEAN && right == Kind.NUMERIC || left == Kind.NUMERIC && right == Kind.BOOLEAN;
        boolean primitive = referential
            ? isPrimitiveValue(instance.type()) && isPrimitiveValue(other.type())
            : isPrimitiveValue(instance.type()) || isPrimitiveValue(other.type());
        if (mixed && primitive) {
            throw fail(operation, describe(instance.type()) + " and " + describe(other.type()),
                "two booleans or two numbers, or a reference");
        }
    }

    /**
     * Rejects a binary math operation on floating point operands that only integers support.
     *
     * @param opType The operation
     * @param left   The left operand, whose type the operation has
     * @param right  The right operand
     */
    static void requireMathOperands(ExpressionDef.MathBinaryOperation.OpType opType, ExpressionDef left, ExpressionDef right) {
        boolean integral = switch (opType) {
            case BITWISE_AND, BITWISE_OR, BITWISE_XOR, BITWISE_LEFT_SHIFT, BITWISE_RIGHT_SHIFT, BITWISE_UNSIGNED_RIGHT_SHIFT -> true;
            default -> false;
        };
        if (integral && strip(left.type()) instanceof TypeDef.Primitive primitive && primitive.isFloatNumber()) {
            throw fail("Math " + opType, operands(left, right), "whole number operands (byte, short, int or long), not float or double");
        }
    }

    /**
     * A message for a binary math operation whose operands are not primitive numbers.
     *
     * @param opType The operation
     * @param left   The left operand
     * @param right  The right operand
     * @return The message
     */
    static String mathOperandsMessage(ExpressionDef.MathBinaryOperation.OpType opType, ExpressionDef left, ExpressionDef right) {
        return message("Math " + opType, operands(left, right), "primitive number operands (byte, short, int, long, float or double)");
    }

    /**
     * Rejects a negation of what is not a number.
     *
     * @param opType  The operation
     * @param operand The operand
     */
    static void requireNegatable(ExpressionDef.MathUnaryOperation.OpType opType, ExpressionDef operand) {
        Kind kind = kind(operand.type());
        if (kind == Kind.VOID || kind == Kind.BOOLEAN || kind == Kind.STRING || kind == Kind.ARRAY) {
            throw fail("Math " + opType, "operand " + describe(operand.type()), "a number");
        }
    }

    /**
     * Rejects an array access on what is not an array, or with an index that is not an int.
     *
     * @param array The array
     * @param index The index
     */
    static void requireArrayAccess(ExpressionDef array, ExpressionDef index) {
        TypeDef arrayType = strip(array.type());
        if (arrayType instanceof TypeDef.Primitive || isKnownClass(arrayType)) {
            throw fail("Array element", "array " + describe(array.type()), "an array");
        }
        TypeDef indexType = strip(index.type());
        Kind indexKind = kind(indexType);
        boolean wide = indexType instanceof TypeDef.Primitive primitive
            && (primitive.equals(TypeDef.Primitive.LONG) || primitive.isFloatNumber());
        if (indexKind == Kind.VOID || indexKind == Kind.BOOLEAN || indexKind == Kind.STRING || indexKind == Kind.ARRAY || wide) {
            throw fail("Array element", "index " + describe(index.type()), "an int index");
        }
    }

    /**
     * Rejects the elements of an array that cannot be stored in it.
     *
     * @param type     The array type
     * @param elements The elements
     */
    static void requireArrayElements(TypeDef.Array type, List<? extends ExpressionDef> elements) {
        TypeDef component = type.dimensions() > 1 ? TypeDef.array(type.componentType(), type.dimensions() - 1) : type.componentType();
        for (int i = 0; i < elements.size(); i++) {
            requireAssignable("New array " + describe(type) + " element " + i, component, elements.get(i));
        }
    }

    /**
     * Rejects a negative array size.
     *
     * @param type The array type
     * @param size The size
     */
    static void requireArraySize(TypeDef.Array type, int size) {
        if (size < 0) {
            throw fail("New array " + describe(type), "size " + size, "a size of 0 or more");
        }
    }

    /**
     * Rejects a constant of a primitive type whose value is no literal of it: {@code null}, a string, or a boolean
     * for a number and a number or a character for a boolean.
     *
     * @param type  The type of the constant
     * @param value The value
     */
    static void requireConstant(TypeDef type, @Nullable Object value) {
        if (!isPrimitiveValue(type)) {
            return;
        }
        if (value == null) {
            throw fail("Constant", "null of " + describe(type), "a value of " + describe(type) + ", not null");
        }
        if (value.getClass().isArray()) {
            // Written as an array of the constants it holds
            return;
        }
        boolean isBoolean = kind(type) == Kind.BOOLEAN;
        boolean literal = isBoolean ? value instanceof Boolean : (value instanceof Number || value instanceof Character);
        if (!literal) {
            throw fail("Constant", value.getClass().getName() + " value " + value + " of " + describe(type),
                isBoolean ? "a Boolean value" : "a Number or a Character value");
        }
    }

    /**
     * Rejects an exception that is certainly not a {@code Throwable}.
     *
     * @param exception The exception
     */
    static void requireThrowable(ExpressionDef exception) {
        Kind kind = kind(exception.type());
        if (kind != Kind.OTHER || isPrimitiveValue(exception.type())) {
            throw fail("Throw", "exception " + describe(exception.type()), "a Throwable");
        }
    }

    /**
     * Rejects a monitor that is not a reference.
     *
     * @param monitor The monitor
     */
    static void requireMonitor(ExpressionDef monitor) {
        if (strip(monitor.type()) instanceof TypeDef.Primitive) {
            throw fail("Synchronized", "monitor " + describe(monitor.type()), "a reference");
        }
    }

    /**
     * Rejects an instantiation of an interface.
     *
     * @param type The type instantiated
     */
    static void requireInstantiable(ClassTypeDef type) {
        if (!(type instanceof ClassTypeDef.ClassName) && type.isInterface()) {
            throw fail("New instance", "type " + describe(type), "a class, not an interface");
        }
    }

    /**
     * Rejects a switch case that yields no result.
     *
     * @param type      The type of the result
     * @param statement The statement of the case
     */
    static void requireYield(TypeDef type, StatementDef statement) {
        if (kind(type) == Kind.VOID) {
            throw fail("Switch yield case", "result " + describe(type), "a result of a type other than void");
        }
        if (statement.flatten().isEmpty()) {
            throw fail("Switch yield case", "an empty statement", "a statement that returns the result or throws");
        }
        // Whether a statement of the case returns or throws, or loops and may not complete, and whether a return has no value
        boolean[] exits = {false};
        boolean[] returnsNothing = {false};
        forEachNested(statement, nested -> {
            if (nested instanceof StatementDef.Return aReturn) {
                exits[0] = true;
                ExpressionDef value = aReturn.expression();
                if (value == null || kind(value.type()) == Kind.VOID) {
                    returnsNothing[0] = true;
                }
            } else if (nested instanceof StatementDef.Throw || nested instanceof StatementDef.While) {
                exits[0] = true;
            }
        });
        if (returnsNothing[0]) {
            throw fail("Switch yield case", "a return without a value", "every return of the case to return its result");
        }
        if (!exits[0]) {
            throw fail("Switch yield case", "a statement that neither returns nor throws", "a statement that returns the result or throws");
        }
    }

    /**
     * Rejects a return of no value from a method that returns one. A value returned from a {@code void} method is
     * kept: the backends discard it.
     *
     * @param method The method
     */
    static void requireReturns(MethodDef method) {
        TypeDef returnType = method.getReturnType();
        if (kind(returnType) == Kind.VOID) {
            return;
        }
        for (StatementDef statement : method.getStatements()) {
            forEachNested(statement, nested -> {
                if (nested instanceof StatementDef.Return aReturn
                    && (aReturn.expression() == null || kind(aReturn.expression().type()) == Kind.VOID)) {
                    throw fail("Return", "no value from the method " + method.getName() + " returning " + describe(returnType),
                        "a value of " + describe(returnType));
                }
            });
        }
    }

    /**
     * Visits a statement and the statements it nests, not those of the expressions it holds: a lambda or a switch
     * yield case returns from itself.
     */
    private static void forEachNested(StatementDef statement, Consumer<StatementDef> action) {
        action.accept(statement);
        switch (statement) {
            case StatementDef.Multi multi -> multi.statements().forEach(s -> forEachNested(s, action));
            case StatementDef.If anIf -> forEachNested(anIf.statement(), action);
            case StatementDef.IfElse ifElse -> {
                forEachNested(ifElse.statement(), action);
                forEachNested(ifElse.elseStatement(), action);
            }
            case StatementDef.While aWhile -> forEachNested(aWhile.statement(), action);
            case StatementDef.Synchronized sync -> forEachNested(sync.statement(), action);
            case StatementDef.Switch aSwitch -> {
                aSwitch.cases().values().forEach(s -> forEachNested(s, action));
                StatementDef defaultCase = aSwitch.defaultCase();
                if (defaultCase != null) {
                    forEachNested(defaultCase, action);
                }
            }
            case StatementDef.Try aTry -> {
                forEachNested(aTry.statement(), action);
                aTry.catches().forEach(c -> forEachNested(c.statement(), action));
                StatementDef finallyStatement = aTry.finallyStatement();
                if (finallyStatement != null) {
                    forEachNested(finallyStatement, action);
                }
            }
            default -> {
                // No nested statements
            }
        }
    }

    /**
     * Whether a value of one kind converts to another: a boolean never converts to a number, nor a number to a boolean.
     */
    private static boolean convertible(Kind source, Kind target) {
        return !(source == Kind.BOOLEAN && target == Kind.NUMERIC || source == Kind.NUMERIC && target == Kind.BOOLEAN);
    }

    private static boolean isNullConstant(ExpressionDef expression) {
        return expression instanceof ExpressionDef.Constant constant && constant.value() == null;
    }

    private static boolean isPrimitiveValue(TypeDef type) {
        return strip(type) instanceof TypeDef.Primitive primitive && !primitive.equals(TypeDef.VOID);
    }

    /**
     * A class type the model knows more of than its name.
     */
    private static boolean isKnownClass(TypeDef type) {
        TypeDef stripped = strip(type);
        return stripped instanceof ClassTypeDef && !(stripped instanceof ClassTypeDef.ClassName)
            && !(stripped instanceof ClassTypeDef.Parameterized parameterized && parameterized.rawType() instanceof ClassTypeDef.ClassName);
    }

    private static Kind kind(TypeDef type) {
        TypeDef stripped = strip(type);
        if (stripped instanceof TypeDef.Primitive primitive) {
            if (primitive.equals(TypeDef.VOID)) {
                return Kind.VOID;
            }
            return primitive.equals(TypeDef.Primitive.BOOLEAN) ? Kind.BOOLEAN : Kind.NUMERIC;
        }
        if (stripped instanceof TypeDef.Array) {
            return Kind.ARRAY;
        }
        if (isKnownClass(stripped)) {
            String name = ((ClassTypeDef) stripped).getName();
            if (name.equals(Boolean.class.getName())) {
                return Kind.BOOLEAN;
            }
            if (NUMERIC_WRAPPERS.contains(name)) {
                return Kind.NUMERIC;
            }
            if (name.equals(String.class.getName())) {
                return Kind.STRING;
            }
        }
        return Kind.OTHER;
    }

    private static TypeDef strip(TypeDef type) {
        TypeDef current = type;
        while (true) {
            if (current instanceof TypeDef.AnnotatedTypeDef annotated) {
                current = annotated.typeDef();
            } else if (current instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
                current = annotated.typeDef();
            } else {
                return current;
            }
        }
    }

    private static String operands(ExpressionDef left, ExpressionDef right) {
        return "left " + describe(left.type()) + ", right " + describe(right.type());
    }

    private static String describe(@Nullable TypeDef type) {
        if (type == null) {
            return "<none>";
        }
        TypeDef stripped = strip(type);
        return switch (stripped) {
            case TypeDef.Primitive primitive -> primitive.name();
            case TypeDef.Array array -> describe(array.componentType()) + "[]".repeat(array.dimensions());
            case ClassTypeDef classTypeDef -> classTypeDef.getName();
            default -> stripped.toString();
        };
    }

    private static String message(String operation, String found, String expected) {
        return operation + ": found " + found + ", expected " + expected;
    }

    private static IllegalArgumentException fail(String operation, String found, String expected) {
        return new IllegalArgumentException(message(operation, found, expected));
    }

    /**
     * The kind of value a type certainly holds.
     */
    private enum Kind {
        /** No value: {@code void}. */
        VOID,
        /** A {@code boolean} or a {@code Boolean}. */
        BOOLEAN,
        /** A number or a character, primitive or boxed. */
        NUMERIC,
        /** A {@code String}. */
        STRING,
        /** An array. */
        ARRAY,
        /** Anything else, or not known. */
        OTHER
    }
}
