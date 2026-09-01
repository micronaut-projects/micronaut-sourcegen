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
import org.jspecify.annotations.Nullable;
import io.micronaut.inject.processing.JavaModelUtils;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Type;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal bytecode type utils.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
@Internal
public final class TypeUtils {

    public static final Type OBJECT_TYPE = Type.getType(Object.class);
    private static final Pattern ARRAY_PATTERN = Pattern.compile("(\\[])+$");

    public static String getMethodDescriptor(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');
        for (ParameterDef parameterDef : methodDef.getParameters()) {
            builder.append(TypeUtils.getType(parameterDef.getType(), objectDef));
        }
        builder.append(')');
        builder.append(TypeUtils.getType(Objects.requireNonNullElse(methodDef.getReturnType(), TypeDef.VOID), objectDef));
        return builder.toString();
    }

    public static Type getType(TypeDef typeDef, @Nullable ObjectDef objectDef) {
        typeDef = ObjectDef.getContextualType(objectDef, typeDef);
        // The annotations a type carries are written separately, they play no part in its erasure
        if (typeDef instanceof TypeDef.AnnotatedTypeDef annotated) {
            return getType(annotated.typeDef(), objectDef);
        }
        if (typeDef instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return getType(annotated.typeDef(), objectDef);
        }
        if (typeDef instanceof TypeDef.Array array) {
            return Type.getType("[".repeat(array.dimensions()) + getType(array.componentType(), objectDef).getDescriptor());
        }
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            return getType(
                parameterized.rawType().getName()
            );
        }
        if (typeDef instanceof ClassTypeDef classTypeDef) {
            return getType(classTypeDef.getName());
        }
        if (typeDef instanceof TypeDef.Primitive primitive) {
            return getType(primitive);
        }
        if (typeDef instanceof TypeDef.Wildcard wildcard) {
            if (!wildcard.lowerBounds().isEmpty()) {
                return getBoundsType(wildcard.lowerBounds(), objectDef);
            }
            if (!wildcard.upperBounds().isEmpty()) {
                return getBoundsType(wildcard.upperBounds(), objectDef);
            }
            return TypeUtils.OBJECT_TYPE;
        }
        if (typeDef instanceof TypeDef.TypeVariable typeVariable) {
            if (typeVariable.bounds().isEmpty()) {
                String name = typeVariable.name();
                if (objectDef instanceof ClassDef classDef) {
                    TypeDef.TypeVariable tvDef = classDef.getTypeVariables().stream()
                        .filter(tv -> tv.name().equals(name)).findFirst()
                        .orElse(null);
                    if (tvDef != null) {
                        return getBoundsType(tvDef.bounds(), objectDef);
                    }
                }
                if (objectDef instanceof InterfaceDef interfaceDef) {
                    TypeDef.TypeVariable tvDef = interfaceDef.getTypeVariables().stream()
                        .filter(tv -> tv.name().equals(name)).findFirst()
                        .orElse(null);
                    if (tvDef != null) {
                        return getBoundsType(tvDef.bounds(), objectDef);
                    }
                }
                if (objectDef instanceof RecordDef recordDef) {
                    TypeDef.TypeVariable tvDef = recordDef.getTypeVariables().stream()
                        .filter(tv -> tv.name().equals(name)).findFirst()
                        .orElse(null);
                    if (tvDef != null) {
                        return getBoundsType(tvDef.bounds(), objectDef);
                    }
                }
                return TypeUtils.OBJECT_TYPE;
            }
            return getBoundsType(typeVariable.bounds(), objectDef);
        }
        throw new IllegalStateException("Unsupported type: " + typeDef);
    }

    private static Type getBoundsType(List<TypeDef> bounds, @Nullable ObjectDef objectDef) {
        // Select first non-object type
        for (TypeDef bound : bounds) {
            Type type = getType(bound, objectDef);
            if (!type.equals(OBJECT_TYPE)) {
                return type;
            }
        }
        return OBJECT_TYPE;
    }

    public static Type getType(TypeDef.Primitive primitive) {
        return Type.getType(JavaModelUtils.NAME_TO_TYPE_MAP.get(primitive.name()));
    }

    private static String getTypeDescriptor(String className, Type... genericTypes) {
        String internalName = getInternalName(className);
        StringBuilder start = new StringBuilder(40);
        Matcher matcher = ARRAY_PATTERN.matcher(className);
        if (matcher.find()) {
            int dimensions = matcher.group(0).length() / 2;
            start.append("[".repeat(dimensions));
        }
        start.append('L').append(internalName);
        if (genericTypes != null && genericTypes.length > 0) {
            start.append('<');
            for (Type genericType : genericTypes) {
                start.append(genericType.getInternalName());
            }
            start.append('>');
        }
        return start.append(';').toString();
    }

    public static Type getType(String className, Type... genericTypes) {
        return Type.getType(getTypeDescriptor(className, genericTypes));
    }

    public static Type getType(ClassTypeDef classTypeDef) {
        return getType(classTypeDef.getName());
    }

    public static Type getType(String className) {
        return Type.getType(getTypeDescriptor(className));
    }

    private static String getInternalName(String className) {
        String newClassName = className.replace('.', '/');
        Matcher matcher = ARRAY_PATTERN.matcher(newClassName);
        if (matcher.find()) {
            newClassName = matcher.replaceFirst("");
        }
        return newClassName;
    }

}
