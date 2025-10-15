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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.PropertyElement;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Java language idioms.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
@Internal
public final class JavaIdioms {

    private static final MethodDef ARRAYS_DEEP_EQUALS = MethodDef.builder("deepEquals")
        .returns(boolean.class)
        .addParameters(TypeDef.OBJECT.array(), TypeDef.OBJECT.array())
        .build();

    private static final MethodDef ARRAYS_DEEP_HASHCODE = MethodDef.builder("deepHashCode")
        .returns(int.class)
        .addParameter(TypeDef.OBJECT.array())
        .build();

    private static final MethodDef OBJECTS_EQUALS = MethodDef.builder("equals")
        .returns(boolean.class)
        .addParameters(Object.class, Object.class)
        .build();

    private static final MethodDef OBJECT_HASHCODE = MethodDef.builder("hashCode")
        .returns(int.class)
        .build();

    private static final ClassTypeDef OBJECTS_TYPE = ClassTypeDef.of(Objects.class);
    private static final ClassTypeDef ARRAYS_TYPE = ClassTypeDef.of(Arrays.class);

    private static final Method STRING_BUILDER_APPEND_STRING = ReflectionUtils.getRequiredMethod(StringBuilder.class, "append", String.class);
    private static final Method STRING_BUILDER_APPEND_OBJECT = ReflectionUtils.getRequiredMethod(StringBuilder.class, "append", Object.class);
    private static final Method STRING_BUILDER_TO_STRING = ReflectionUtils.getRequiredMethod(StringBuilder.class, "toString");

    /**
     * Concat strings using {@link StringBuilder}.
     *
     * @param stringExpressions The expression
     * @return The string builder expression
     */
    public static ExpressionDef concatStrings(ExpressionDef... stringExpressions) {
        return concatStrings(Arrays.asList(stringExpressions));
    }

    /**
     * Concat strings using {@link StringBuilder}.
     *
     * @param stringExpressions The expression
     * @return The string builder expression
     */
    public static ExpressionDef concatStrings(List<? extends ExpressionDef> stringExpressions) {
        if (stringExpressions.isEmpty()) {
            return ExpressionDef.constant("");
        }
        if (stringExpressions.size() == 1) {
            return convertToStringIfNeeded(stringExpressions.get(0));
        }
        ExpressionDef stringBuilderExp = null;
        for (ExpressionDef expression : stringExpressions) {
            if (stringBuilderExp == null) {
                stringBuilderExp = ClassTypeDef.of(StringBuilder.class).instantiate(
                    convertToStringIfNeeded(expression)
                );
            } else if (expression.type().equals(TypeDef.STRING)) {
                stringBuilderExp = stringBuilderExp.invoke(STRING_BUILDER_APPEND_STRING, expression);
            } else if (expression.type().isArray()) {
                stringBuilderExp = stringBuilderExp.invoke(STRING_BUILDER_APPEND_STRING, convertToStringIfNeeded(expression));
            } else {
                stringBuilderExp = stringBuilderExp.invoke(STRING_BUILDER_APPEND_OBJECT, expression);
            }
        }
        return stringBuilderExp.invoke(STRING_BUILDER_TO_STRING);
    }

    /**
     * Convert the expression to {@link String} if it's not one.
     * @param expression The expression
     * @return The string expression
     */
    public static ExpressionDef convertToStringIfNeeded(ExpressionDef expression) {
        TypeDef type = expression.type();
        if (type instanceof ClassTypeDef classTypeDef && classTypeDef.getName().equals(String.class.getName())) {
            return expression;
        }
        if (type.isArray()) {
            return ClassTypeDef.of(Arrays.class).invokeStatic("toString", TypeDef.STRING, expression);
        }
        return ClassTypeDef.of(String.class).invokeStatic("valueOf", List.of(TypeDef.OBJECT), TypeDef.STRING, expression);
    }

    /**
     * The equals structurally idiom.
     *
     * @param equalsStructurally The expression
     * @return The idiom expression
     */
    public static ExpressionDef equalsStructurally(ExpressionDef.EqualsStructurally equalsStructurally) {
        ExpressionDef left = equalsStructurally.instance();
        ExpressionDef right = equalsStructurally.other();
        return equalsStructurally(left, right);
    }

    /**
     * The equals structurally idiom.
     *
     * @param left The left expression
     * @param right The left expression
     * @return The idiom expression
     * @since 1.5
     */
    public static ExpressionDef equalsStructurally(ExpressionDef left, ExpressionDef right) {
        var type = left.type();
        if (type instanceof TypeDef.Array array) {
            if (array.dimensions() > 1) {
                return ARRAYS_TYPE
                    .invokeStatic(
                        ARRAYS_DEEP_EQUALS,
                        left,
                        right
                    );
            } else {
                return ARRAYS_TYPE
                    .invokeStatic(
                        "equals",
                        TypeDef.Primitive.BOOLEAN,
                        left,
                        right
                    );
            }
        }
        return OBJECTS_TYPE.invokeStatic(OBJECTS_EQUALS, left, right);
    }

    /**
     * The hashCode idiom.
     *
     * @param invokeHashCodeMethod The expression
     * @return The idiom expression
     */
    public static ExpressionDef hashCode(ExpressionDef.InvokeHashCodeMethod invokeHashCodeMethod) {
        ExpressionDef instance = invokeHashCodeMethod.instance();
        var type = instance.type();
        TypeDef.Primitive primitiveIntType = TypeDef.Primitive.INT;
        if (type instanceof TypeDef.Array array) {
            if (array.dimensions() > 1) {
                return ARRAYS_TYPE.invokeStatic(ARRAYS_DEEP_HASHCODE, instance);
            }
            return ARRAYS_TYPE.invokeStatic("hashCode", primitiveIntType, instance
            );
        }
        if (type instanceof TypeDef.Primitive primitive) {
            return primitive.wrapperType()
                .invokeStatic("hashCode", primitiveIntType, instance);
        }
        return instance.ifNull(
            primitiveIntType.constant(0),
            instance.invoke(OBJECT_HASHCODE)
        );
    }

    /**
     * The get class idiom.
     *
     * @param invokeGetClassMethod The expression
     * @return The idiom expression
     */
    public static ExpressionDef getClass(ExpressionDef.InvokeGetClassMethod invokeGetClassMethod) {
        return invokeGetClassMethod.instance().invoke("getClass", TypeDef.CLASS);
    }

    /**
     * The get property value idiom.
     *
     * @param getPropertyValue The expression
     * @return The idiom expression
     */
    public static ExpressionDef getPropertyValue(ExpressionDef.GetPropertyValue getPropertyValue) {
        PropertyElement propertyElement = getPropertyValue.propertyElement();
        Optional<? extends MemberElement> readPropertyMember = propertyElement.getReadMember();
        if (readPropertyMember.isEmpty()) {
            throw new IllegalStateException("Read member not found for property: " + propertyElement);
        }
        MemberElement memberElement = readPropertyMember.get();
        if (memberElement instanceof MethodElement methodElement) {
            return getPropertyValue.instance().invoke(methodElement);
        }
        if (memberElement instanceof FieldElement fieldElement) {
            return getPropertyValue.instance().field(fieldElement);
        }
        throw new IllegalStateException("Unrecognized property read element: " + propertyElement);
    }

}
