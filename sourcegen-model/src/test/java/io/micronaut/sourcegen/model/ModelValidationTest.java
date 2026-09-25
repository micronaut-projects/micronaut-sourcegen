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

import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.ADDITION;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_AND;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_XOR;
import static io.micronaut.sourcegen.model.ExpressionDef.MathUnaryOperation.OpType.NEGATE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The shapes the model rejects when they are constructed, because no backend writes them, and the borderline shapes it
 * keeps accepting.
 */
class ModelValidationTest {

    private static final TypeDef.Primitive INT = TypeDef.Primitive.INT;
    private static final TypeDef.Primitive BOOLEAN = TypeDef.Primitive.BOOLEAN;

    private static final ExpressionDef ONE = ExpressionDef.constant(1);
    private static final ExpressionDef ONE_LONG = ExpressionDef.constant(1L);
    private static final ExpressionDef ONE_DOUBLE = ExpressionDef.constant(1.0d);
    private static final ExpressionDef ONE_FLOAT = ExpressionDef.constant(1.0f);
    private static final ExpressionDef TRUE = ExpressionDef.trueValue();
    private static final ExpressionDef TEXT = ExpressionDef.constant("text");
    private static final VariableDef.Local INT_LOCAL = new VariableDef.Local("i", INT);
    private static final VariableDef.Local LONG_LOCAL = new VariableDef.Local("l", TypeDef.Primitive.LONG);
    private static final VariableDef.Local OBJECT_LOCAL = new VariableDef.Local("o", TypeDef.OBJECT);
    private static final VariableDef.Local BOXED_INT = new VariableDef.Local("boxed", TypeDef.Primitive.INT_WRAPPER);
    private static final VariableDef.Local BOXED_BOOLEAN = new VariableDef.Local("flag", TypeDef.Primitive.BOOLEAN_WRAPPER);
    private static final VariableDef.Local INTS = new VariableDef.Local("ints", INT.array());
    private static final VariableDef.Local VARIABLE = new VariableDef.Local("t", TypeDef.variable("T"));
    private static final VariableDef.Local NAMED = new VariableDef.Local("n", ClassTypeDef.of("example.Unknown"));
    private static final VariableDef.Local RUNNABLE = new VariableDef.Local("r", ClassTypeDef.of(Runnable.class));
    /** An expression without a value: {@code r.run()}. */
    private static final ExpressionDef NOTHING = RUNNABLE.invoke("run", TypeDef.VOID);
    private static final StatementDef STATEMENT = RUNNABLE.invoke("run", TypeDef.VOID);

    static Stream<Arguments> invalidShapes() {
        return Stream.of(
            // Conditions
            invalid("isTrue() on an int", () -> ONE.isTrue(),
                "Is true: found condition int, expected a boolean or a Boolean"),
            invalid("isFalse() on a String", () -> TEXT.isFalse(),
                "Is false: found condition java.lang.String, expected a boolean or a Boolean"),
            invalid("IsTrue of an Integer", () -> new ExpressionDef.IsTrue(BOXED_INT),
                "Is true: found condition java.lang.Integer, expected a boolean or a Boolean"),
            invalid("if on an int", () -> new StatementDef.If(ONE, STATEMENT),
                "If: found condition int, expected a boolean or a Boolean"),
            invalid("if-else on an array", () -> new StatementDef.IfElse(INTS, STATEMENT, STATEMENT),
                "If-else: found condition int[], expected a boolean or a Boolean"),
            invalid("while on void", () -> new StatementDef.While(NOTHING, STATEMENT),
                "While: found condition void, expected a boolean or a Boolean"),
            invalid("if-else expression on a String", () -> new ExpressionDef.IfElse(TEXT, ONE, ONE),
                "If-else expression: found condition java.lang.String, expected a boolean or a Boolean"),

            // Math
            invalid("bitwise and of doubles", () -> ONE_DOUBLE.math(BITWISE_AND, ONE_DOUBLE),
                "Math BITWISE_AND: found left double, right double, expected whole number operands (byte, short, int or long), not float or double"),
            invalid("shift of a float", () -> ONE_FLOAT.math(BITWISE_LEFT_SHIFT, ONE),
                "Math BITWISE_LEFT_SHIFT: found left float, right int, expected whole number operands (byte, short, int or long), not float or double"),
            invalid(IllegalStateException.class, "arithmetic on a boolean", () -> TRUE.math(ADDITION, TRUE),
                "Math ADDITION: found left boolean, right boolean, expected primitive number operands"),
            invalid(IllegalStateException.class, "arithmetic on a char", () -> ExpressionDef.constant('a').math(ADDITION, ONE),
                "Math ADDITION: found left char, right int, expected primitive number operands"),
            invalid("negation of a boolean", () -> TRUE.math(NEGATE),
                "Math NEGATE: found operand boolean, expected a number"),
            invalid("negation of a String", () -> TEXT.math(NEGATE),
                "Math NEGATE: found operand java.lang.String, expected a number"),
            invalid("concatenation of void", () -> TEXT.stringConcat(NOTHING),
                "String concatenation: found right void, expected a value, not void"),
            invalid("comparison with void", () -> NOTHING.compare(ExpressionDef.ComparisonOperation.OpType.EQUAL_TO, ONE),
                "Comparison EQUAL_TO: found left void, expected a value, not void"),

            // Equality of a boolean and a number
            invalid("referential equality of an int and a boolean", () -> ONE.equalsReferentially(TRUE),
                "Equals referentially: found int and boolean, expected two booleans or two numbers, or a reference"),
            invalid("referential inequality of a boolean and a long", () -> TRUE.notEqualsReferentially(ONE_LONG),
                "Not equals referentially: found boolean and long, expected two booleans or two numbers, or a reference"),
            invalid("structural equality of a boolean and an int", () -> TRUE.equalsStructurally(ONE),
                "Equals structurally: found boolean and int, expected two booleans or two numbers, or a reference"),
            invalid("structural inequality of a double and a Boolean", () -> ONE_DOUBLE.notEqualsStructurally(BOXED_BOOLEAN),
                "Not equals structurally: found double and java.lang.Boolean, expected two booleans or two numbers, or a reference"),

            // Casts and constants
            invalid("cast of an int to boolean", () -> ONE.cast(BOOLEAN),
                "Cast: found int to boolean, expected a conversion between a boolean and a boolean, or between numbers and characters"),
            invalid("cast of a Boolean to int", () -> BOXED_BOOLEAN.cast(INT),
                "Cast: found java.lang.Boolean to int, expected a conversion between a boolean and a boolean, or between numbers and characters"),
            invalid("cast of void", () -> NOTHING.cast(TypeDef.OBJECT),
                "Cast to java.lang.Object: found operand void, expected a value, not void"),
            invalid("null constant of an int", () -> new ExpressionDef.Constant(INT, null),
                "Constant: found null of int, expected a value of int, not null"),
            invalid("String constant of an int", () -> new ExpressionDef.Constant(INT, "1"),
                "Constant: found java.lang.String value 1 of int, expected a Number or a Character value"),
            invalid("number constant of a boolean", () -> new ExpressionDef.Constant(BOOLEAN, 1),
                "Constant: found java.lang.Integer value 1 of boolean, expected a Boolean value"),

            // Arrays
            invalid("array element of an int", () -> new ExpressionDef.ArrayElement(INT_LOCAL, INT, ONE),
                "Array element: found array int, expected an array"),
            invalid("array element of a String", () -> new ExpressionDef.ArrayElement(TEXT, INT, ONE),
                "Array element: found array java.lang.String, expected an array"),
            invalid("array index of a long", () -> new ExpressionDef.ArrayElement(INTS, INT, ONE_LONG),
                "Array element: found index long, expected an int index"),
            invalid("array index of a boolean", () -> new ExpressionDef.ArrayElement(INTS, INT, TRUE),
                "Array element: found index boolean, expected an int index"),
            invalid("array of a negative size", () -> INT.array().instantiate(-1),
                "New array int[]: found size -1, expected a size of 0 or more"),
            invalid("boolean element of an int array", () -> INT.array().instantiate(List.of(TRUE)),
                "New array int[] element 0: found boolean to int, expected a value convertible to int"),

            // Assignments
            invalid("assignment of void", () -> OBJECT_LOCAL.assign(NOTHING),
                "Assign o: found value void, expected a value, not void"),
            invalid("local of void", () -> new VariableDef.Local("v", TypeDef.VOID).defineAndAssign(ONE),
                "Define and assign v: found target void, expected a variable of a type other than void"),
            invalid("boolean put in an int field", () -> OBJECT_LOCAL.field("count", INT).put(TRUE),
                "Put field count: found boolean to int, expected a value convertible to int"),
            invalid("int put in a boolean static field", () -> ClassTypeDef.of("example.Holder").getStaticField("flag", BOOLEAN).put(ONE),
                "Put static field flag: found int to boolean, expected a value convertible to boolean"),
            invalid("null assigned to an int", () -> INT_LOCAL.assign(ExpressionDef.nullValue()),
                "Assign i: found null to int, expected a value of int, not null"),

            // Invocations
            invalid("argument of void", () -> OBJECT_LOCAL.invoke("accept", TypeDef.VOID, NOTHING),
                "Invoke accept: found argument 0 void, expected a value, not void"),
            invalid("instance of an interface", () -> ClassTypeDef.of(Runnable.class).instantiate(),
                "New instance: found type java.lang.Runnable, expected a class, not an interface"),
            invalid(IllegalStateException.class, "new instance with more values than parameters",
                () -> new ExpressionDef.NewInstance(ClassTypeDef.OBJECT, List.of(), List.of(ONE)),
                "parameters: 0 doesn't match values provided: 1"),

            // Other statements
            invalid("throw of an int", () -> ONE.doThrow(),
                "Throw: found exception int, expected a Throwable"),
            invalid("throw of a String", () -> TEXT.doThrow(),
                "Throw: found exception java.lang.String, expected a Throwable"),
            invalid("synchronized on an int", () -> new StatementDef.Synchronized(ONE, STATEMENT),
                "Synchronized: found monitor int, expected a reference"),

            // Switch yield cases
            invalid("switch yield case without statements", () -> new ExpressionDef.SwitchYieldCase(INT, StatementDef.multi()),
                "Switch yield case: found an empty statement, expected a statement that returns the result or throws"),
            invalid("switch yield case without a result", () -> new ExpressionDef.SwitchYieldCase(INT, STATEMENT),
                "Switch yield case: found a statement that neither returns nor throws, expected a statement that returns the result or throws"),
            invalid("switch yield case returning nothing", () -> new ExpressionDef.SwitchYieldCase(INT, new StatementDef.Return(null)),
                "Switch yield case: found a return without a value, expected every return of the case to return its result"),
            invalid("switch yield case of void", () -> new ExpressionDef.SwitchYieldCase(TypeDef.VOID, ONE.returning()),
                "Switch yield case: found result void, expected a result of a type other than void"),

            // Returns
            invalid("nothing returned from an int method",
                () -> MethodDef.builder("count").returns(INT).addStatement(new StatementDef.Return(null)).build(),
                "Return: found no value from the method count returning int, expected a value of int"),
            invalid("void returned from a nested branch of a String method",
                () -> MethodDef.builder("text").returns(TypeDef.STRING)
                    .addStatement(new StatementDef.If(TRUE, NOTHING.returning())).build(),
                "Return: found no value from the method text returning java.lang.String, expected a value of java.lang.String")
        );
    }

    static Stream<Arguments> borderlineValidShapes() {
        return Stream.of(
            // A condition the model does not know to be something else than a boolean
            valid("isTrue() on a Boolean", () -> BOXED_BOOLEAN.isTrue()),
            valid("isTrue() on an Object", () -> OBJECT_LOCAL.isTrue()),
            valid("isTrue() on a type variable", () -> VARIABLE.isTrue()),
            valid("isTrue() on a class known by its name", () -> new VariableDef.Local("b", ClassTypeDef.of("java.lang.Integer")).isTrue()),
            valid("if on a Boolean", () -> new StatementDef.If(BOXED_BOOLEAN, STATEMENT)),

            // Math the backends convert
            valid("int plus long", () -> ONE.math(ADDITION, ONE_LONG)),
            valid("long shifted by an int", () -> ONE_LONG.math(BITWISE_LEFT_SHIFT, ONE)),
            valid("int xor with a double right operand, cast to int", () -> ONE.math(BITWISE_XOR, ONE_DOUBLE)),
            valid("double plus double", () -> ONE_DOUBLE.math(ADDITION, ONE_DOUBLE)),
            valid("negation of an Integer", () -> BOXED_INT.math(NEGATE)),
            valid("negation of a char", () -> ExpressionDef.constant('a').math(NEGATE)),
            valid("negation of a type variable", () -> VARIABLE.math(NEGATE)),
            valid("concatenation of a String and an int", () -> TEXT.stringConcat(ONE)),

            // Equality the backends compare as references
            valid("referential equality of a Boolean and an Integer", () -> BOXED_BOOLEAN.equalsReferentially(BOXED_INT)),
            valid("referential equality of a boolean and an Object", () -> TRUE.equalsReferentially(OBJECT_LOCAL)),
            valid("referential equality of an int and a long", () -> ONE.equalsReferentially(ONE_LONG)),
            valid("structural equality of a Boolean and an Integer", () -> BOXED_BOOLEAN.equalsStructurally(BOXED_INT)),
            valid("structural equality of a boolean and an Object", () -> TRUE.equalsStructurally(OBJECT_LOCAL)),

            // Casts
            valid("cast of an int to long", () -> ONE.cast(TypeDef.Primitive.LONG)),
            valid("cast of a char to int", () -> ExpressionDef.constant('a').cast(INT)),
            valid("cast of an Object to int", () -> OBJECT_LOCAL.cast(INT)),
            valid("cast of an Object to boolean", () -> OBJECT_LOCAL.cast(BOOLEAN)),
            valid("cast of a Boolean to boolean", () -> BOXED_BOOLEAN.cast(BOOLEAN)),
            valid("cast of an int to Integer", () -> ONE.cast(TypeDef.Primitive.INT_WRAPPER)),
            valid("cast of an int to Number", () -> ONE.cast(Number.class)),
            valid("cast of a boolean to Object", () -> TRUE.cast(TypeDef.OBJECT)),
            valid("cast of an int to a type variable", () -> ONE.cast(TypeDef.variable("T"))),
            valid("cast of an int to a class known by its name", () -> ONE.cast(ClassTypeDef.of("java.lang.String"))),
            valid("cast of null to an Integer", () -> ExpressionDef.nullValue().cast(TypeDef.Primitive.INT_WRAPPER)),
            valid("cast of an int to String, boxed and checked", () -> ONE.cast(TypeDef.STRING)),
            valid("cast of a String to long, unboxed through Number", () -> TEXT.cast(TypeDef.Primitive.LONG)),
            valid("cast of an int array to int", () -> INTS.cast(INT)),
            valid("cast of void to void", () -> NOTHING.cast(TypeDef.VOID)),
            valid("cast of a value to void", () -> ONE.cast(TypeDef.VOID)),

            // Constants
            valid("int zero constant of a char", () -> new ExpressionDef.Constant(TypeDef.Primitive.CHAR, 0)),
            valid("char constant of an int", () -> new ExpressionDef.Constant(INT, 'a')),
            valid("array constant", () -> new ExpressionDef.Constant(INT.array(), new int[] {1, 2})),
            valid("null constant of an Integer", () -> new ExpressionDef.Constant(TypeDef.Primitive.INT_WRAPPER, null)),

            // Arrays
            valid("array element of a type variable", () -> new ExpressionDef.ArrayElement(VARIABLE, TypeDef.OBJECT, ONE)),
            valid("array element of a class known by its name", () -> new ExpressionDef.ArrayElement(NAMED, TypeDef.OBJECT, ONE)),
            valid("array index of a char", () -> new ExpressionDef.ArrayElement(INTS, INT, ExpressionDef.constant('a'))),
            valid("array index of an Integer", () -> new ExpressionDef.ArrayElement(INTS, INT, BOXED_INT)),
            valid("array of size zero", () -> INT.array().instantiate(0)),
            valid("long array of int elements", () -> TypeDef.Primitive.LONG.array().instantiate(List.of(ONE))),
            valid("two dimensional array of arrays", () -> INT.array(2).instantiate(List.of(INTS))),

            // Assignments
            valid("int assigned to a long", () -> LONG_LOCAL.assign(ONE)),
            valid("int assigned to an Object", () -> OBJECT_LOCAL.assign(ONE)),
            valid("boolean assigned to an Object", () -> OBJECT_LOCAL.assign(TRUE)),
            valid("null assigned to an Integer", () -> BOXED_INT.assign(ExpressionDef.nullValue())),
            valid("Object assigned to an int", () -> INT_LOCAL.assign(OBJECT_LOCAL)),

            // Statements
            valid("throw of an Object", () -> OBJECT_LOCAL.doThrow()),
            valid("throw of a type variable", () -> VARIABLE.doThrow()),
            valid("synchronized on an Object", () -> new StatementDef.Synchronized(OBJECT_LOCAL, STATEMENT)),
            valid("instance of a class known by its name", () -> ClassTypeDef.of("example.Unknown").instantiate()),
            valid("instance of a class", () -> ClassTypeDef.of(Object.class).instantiate()),
            valid("switch yield case that throws", () -> new ExpressionDef.SwitchYieldCase(INT,
                ClassTypeDef.of(IllegalStateException.class).instantiate().doThrow())),
            valid("switch yield case that loops", () -> new ExpressionDef.SwitchYieldCase(INT,
                new StatementDef.While(TRUE, STATEMENT))),
            valid("switch yield case returning from both branches", () -> new ExpressionDef.SwitchYieldCase(INT,
                new StatementDef.IfElse(BOXED_BOOLEAN, ONE.returning(), ONE.returning()))),

            // Returns
            valid("value returned from a void method, discarded",
                () -> MethodDef.builder("run").returns(TypeDef.VOID).addStatement(TEXT.returning()).build()),
            valid("void invocation returned from a void method",
                () -> MethodDef.builder("run").returns(TypeDef.VOID).addStatement(NOTHING.returning()).build()),
            valid("return without a value from a constructor",
                () -> MethodDef.constructor().addStatement(new StatementDef.Return(null)).build()),
            valid("switch yield case in a void method", () -> MethodDef.builder("run").returns(TypeDef.VOID)
                .addStatement(new ExpressionDef.Switch(INT_LOCAL, INT, Map.of(), new ExpressionDef.SwitchYieldCase(INT, ONE.returning()))
                    .newLocal("result"))
                .build()),
            valid("return type inferred from the last return",
                () -> MethodDef.builder("count").addStatement(ONE.returning()).build())
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidShapes")
    void rejectsInvalidShape(String shape, Class<? extends RuntimeException> exception, Executable construction, String message) {
        RuntimeException e = assertThrows(exception, construction, shape);
        assertEquals(exception, e.getClass(), shape);
        if (!e.getMessage().contains(message)) {
            assertEquals(message, e.getMessage(), shape);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("borderlineValidShapes")
    void acceptsBorderlineShape(String shape, Executable construction) {
        assertDoesNotThrow(construction, shape);
    }

    private static Arguments invalid(String shape, Executable construction, String message) {
        return invalid(IllegalArgumentException.class, shape, construction, message);
    }

    private static Arguments invalid(Class<? extends RuntimeException> exception, String shape, Executable construction, String message) {
        return Arguments.of(shape, exception, construction, message);
    }

    private static Arguments valid(String shape, Construction construction) {
        return Arguments.of(shape, (Executable) construction::construct);
    }

    /**
     * Constructs a model shape.
     */
    @FunctionalInterface
    private interface Construction extends Serializable {
        Object construct();
    }
}
