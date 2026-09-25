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
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.sourcegen.javapoet.CodeBlock;
import io.micronaut.sourcegen.javapoet.Util;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.util.stream.IntStream;


/**
 * The literals of the Java source generator: a constant of the model written as the bytecode writers push it. The
 * class of the value decides, as it does for them, except that a primitive or a box the model types it as is kept:
 * {@code (short) 3} for a {@code short}, not the {@code int} literal {@code 3}.
 *
 * @since 2.3
 */
@Internal
final class JavaLiterals {

    private final JavaTypeRenderer types;
    private final JavaPoetNames names;

    JavaLiterals(JavaExpressionRenderer expressions) {
        this.types = expressions.types();
        this.names = expressions.context().names();
    }

    CodeBlock render(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, ExpressionDef.Constant constant) {
        TypeDef type = constant.type();
        Object value = constant.value();
        if (value == null) {
            return CodeBlock.of("null");
        }
        if (value.getClass().isArray()) {
            // The bytecode creates the array of the value's class, of the constants of its elements. An initializer
            // narrows an int constant as an assignment does
            Class<?> component = value.getClass().getComponentType();
            boolean narrowed = component == byte.class || component == short.class;
            CodeBlock elements = IntStream.range(0, Array.getLength(value))
                .mapToObj(i -> narrowed ? CodeBlock.of("$L", Array.get(value, i))
                    : render(objectDef, methodDef, ExpressionDef.constant(Array.get(value, i))))
                .collect(CodeBlock.joining(", "));
            return CodeBlock.concat(CodeBlock.of("new $T {", types.asType(TypeDef.of(value.getClass()), null)), elements,
                CodeBlock.of("}"));
        }
        if (value instanceof Enum<?> anEnum) {
            // Of the enum that declares it: a constant with a body is of an anonymous subclass
            return CodeBlock.of("$T.$L", types.asType(TypeDef.of(anEnum.getDeclaringClass()), null), anEnum.name());
        }
        if (type instanceof ClassTypeDef classTypeDef && classTypeDef.isEnum()) {
            // A constant of a generated enum, named
            return CodeBlock.of("$T.$L", types.asType(classTypeDef, null), value.toString());
        }
        TypeDef.Primitive primitive = literalPrimitive(constant);
        if (primitive != null) {
            return primitive(primitive, value);
        }
        return switch (value) {
            case String string -> CodeBlock.of("$S", string);
            case TypeDef typeDef -> CodeBlock.of("$L.class", getClassName(JavaTypes.erasure(typeDef, objectDef, methodDef)));
            case Class<?> aClass -> CodeBlock.of("$L.class", getClassName(TypeDef.of(aClass)));
            default -> CodeBlock.of("$L", value);
        };
    }

    /**
     * A constant of a box the bytecode pushes as an object, written as one: {@code Integer.valueOf(5)} is a reference,
     * which a method can be called on and which compares by identity, where the literal {@code 5} is a number.
     *
     * @return The boxed constant, or {@code null} where the constant is no boxed value of a reference type
     */
    @Nullable
    CodeBlock renderBoxed(@Nullable ObjectDef objectDef, @Nullable MethodDef methodDef, ExpressionDef.Constant constant) {
        Object value = constant.value();
        if (!isBoxedValue(constant) || value == null) {
            return null;
        }
        TypeDef.Primitive primitive = JavaTypes.unboxedOf(constant.type());
        if (primitive == null) {
            primitive = TypeDef.primitive(ReflectionUtils.getPrimitiveType(value.getClass()));
        }
        return CodeBlock.of("$T.valueOf($L)", types.asType(primitive.wrapperType(), null),
            render(objectDef, methodDef, new ExpressionDef.Constant(primitive, value)));
    }

    /**
     * Whether a constant is a number, a character or a boolean the model types as a reference: the bytecode boxes it.
     */
    static boolean isBoxedValue(ExpressionDef expression) {
        return expression instanceof ExpressionDef.Constant constant && !(constant.type() instanceof TypeDef.Primitive)
            && (constant.value() instanceof Number || constant.value() instanceof Character || constant.value() instanceof Boolean);
    }

    /**
     * The primitive a constant is written as a literal of: the one or the box the model types it as, else the one of
     * the box its value is.
     *
     * @param constant The constant
     * @return The primitive, or {@code null} for a constant that is no number, character or boolean
     */
    static TypeDef.@Nullable Primitive literalPrimitive(ExpressionDef.Constant constant) {
        Object value = constant.value();
        if (!(value instanceof Number || value instanceof Character || value instanceof Boolean)) {
            return null;
        }
        TypeDef type = constant.type();
        TypeDef.Primitive primitive = type instanceof TypeDef.Primitive p ? p : JavaTypes.unboxedOf(type);
        if (primitive != null) {
            return primitive;
        }
        return switch (value) {
            case Character _ -> TypeDef.Primitive.CHAR;
            case Boolean _ -> TypeDef.Primitive.BOOLEAN;
            case Byte _ -> TypeDef.Primitive.BYTE;
            case Short _ -> TypeDef.Primitive.SHORT;
            case Integer _ -> TypeDef.Primitive.INT;
            case Long _ -> TypeDef.Primitive.LONG;
            case Float _ -> TypeDef.Primitive.FLOAT;
            case Double _ -> TypeDef.Primitive.DOUBLE;
            default -> null;
        };
    }

    /**
     * @param constant The constant
     * @return How tightly its literal binds, see {@link JavaPrecedence}
     */
    static int precedence(ExpressionDef.Constant constant) {
        Object value = constant.value();
        if (value instanceof String string) {
            // JavaPoet writes a string of several lines as a concatenation of one literal per line
            int newline = string.indexOf('\n');
            return newline != -1 && newline < string.length() - 1 ? JavaPrecedence.ADDITIVE : JavaPrecedence.POSTFIX;
        }
        TypeDef.Primitive primitive = literalPrimitive(constant);
        if (primitive == null) {
            return JavaPrecedence.POSTFIX;
        }
        boolean cast = primitive.equals(TypeDef.Primitive.BYTE) || primitive.equals(TypeDef.Primitive.SHORT)
            || primitive.equals(TypeDef.Primitive.CHAR) && !(value instanceof Character);
        return cast || isNegativeLiteral(constant) ? JavaPrecedence.UNARY : JavaPrecedence.POSTFIX;
    }

    /**
     * @param constant The constant
     * @return Whether its literal starts with a minus
     */
    static boolean isNegativeLiteral(ExpressionDef.Constant constant) {
        TypeDef.Primitive primitive = literalPrimitive(constant);
        if (primitive == null || !(constant.value() instanceof Number number)) {
            return false;
        }
        return switch (primitive.name()) {
            case "int" -> number.intValue() < 0;
            case "long" -> number.longValue() < 0;
            // A negative zero is written with its sign, a non-finite value as the constant of its box
            case "float", "double" -> Double.isFinite(number.doubleValue())
                && (number.doubleValue() < 0 || Double.doubleToRawLongBits(number.doubleValue()) == Long.MIN_VALUE);
            default -> false;
        };
    }

    private CodeBlock primitive(TypeDef.Primitive primitive, Object value) {
        if (value instanceof Boolean bool) {
            return CodeBlock.of("$L", bool);
        }
        Number number = value instanceof Character character ? Integer.valueOf(character) : (Number) value;
        return switch (primitive.name()) {
            case "char" -> value instanceof Character character
                ? CodeBlock.of("$L", "'" + Util.characterLiteralWithoutSingleQuotes(character) + "'")
                : CodeBlock.of("(char) $L", number.intValue());
            case "byte" -> CodeBlock.of("(byte) $L", number.byteValue());
            case "short" -> CodeBlock.of("(short) $L", number.shortValue());
            case "int" -> CodeBlock.of("$L", number.intValue());
            case "long" -> CodeBlock.of("$Ll", number.longValue());
            case "float" -> nonFinite(Float.class, number.doubleValue(), CodeBlock.of("$Lf",
                number instanceof Double ? Float.toString(number.floatValue()) : number.toString()));
            case "double" -> nonFinite(Double.class, number.doubleValue(), CodeBlock.of("$Ld",
                number instanceof Float ? Double.toString(number.doubleValue()) : number.toString()));
            case "boolean" -> CodeBlock.of("$L", value);
            default -> throw new IllegalStateException("Unrecognized primitive constant: " + primitive + " " + value);
        };
    }

    /**
     * A non-finite value has no literal, only the constants of its box.
     */
    private CodeBlock nonFinite(Class<?> box, double value, CodeBlock finite) {
        if (Double.isNaN(value)) {
            return CodeBlock.of("$T.NaN", box);
        }
        if (Double.isInfinite(value)) {
            return CodeBlock.of("$T.$L", box, value > 0 ? "POSITIVE_INFINITY" : "NEGATIVE_INFINITY");
        }
        return finite;
    }

    private String getClassName(TypeDef typeDef) {
        return switch (typeDef) {
            case ClassTypeDef classType -> names.asClassType(classType).canonicalName();
            case TypeDef.Primitive primitive -> primitive.name();
            case TypeDef.Array array -> getClassName(array.componentType()) + "[]".repeat(array.dimensions());
            case null, default ->
                throw new IllegalStateException("Unrecognized type def: " + typeDef);
        };
    }
}
