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
package io.micronaut.sourcegen;

import io.micronaut.inject.processing.JavaModelUtils;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Type;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TypeUtils {

    private static final Pattern ARRAY_PATTERN = Pattern.compile("(\\[])+$");

    public static Type getType(TypeDef typeDef) {
        if (typeDef instanceof TypeDef.Array array) {
            return Type.getType("[".repeat(array.dimensions()) + 'L' +  getType(array.componentType()).getInternalName() + ";");
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
            return Type.getType(JavaModelUtils.NAME_TO_TYPE_MAP.get(primitive.name()));
        }
        if (typeDef instanceof TypeDef.TypeVariable typeVariable) {
            return Type.getType(Object.class);
        }
        throw new IllegalStateException("Unsupported type: " + typeDef);
    }

    private static String getConstructorDescriptor(Class<?>... argumentTypes) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');

        for (Class<?> argumentType : argumentTypes) {
            builder.append(TypeUtils.getTypeDescriptor(argumentType.getName()));
        }

        return builder.append(")V").toString();
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

    private static Type getType(String className, Type... genericTypes) {
        return Type.getType(getTypeDescriptor(className, genericTypes));
    }

    public static Type getType(ClassTypeDef classTypeDef) {
        return Type.getType(getTypeDescriptor(classTypeDef.getName()));
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
