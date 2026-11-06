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
import io.micronaut.sourcegen.bytecode.core.EnclosingScope;
import org.jspecify.annotations.Nullable;
import io.micronaut.inject.processing.JavaModelUtils;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.objectweb.asm.Type;

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

    /**
     * The descriptor of a method outside the write of an inner class: no variables of an enclosing class are in scope.
     *
     * @param objectDef The definition, if any
     * @param methodDef The method
     * @return The descriptor
     */
    public static String getMethodDescriptor(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        return getMethodDescriptor(objectDef, methodDef, EnclosingScope.NONE);
    }

    public static String getMethodDescriptor(@Nullable ObjectDef objectDef, MethodDef methodDef, EnclosingScope enclosingScope) {
        return io.micronaut.sourcegen.bytecode.core.TypeUtils.getMethodDescriptor(objectDef, methodDef, enclosingScope);
    }

    /**
     * The type of a value in a method body, erased in the scope of the method: a variable it declares shadows one of
     * the class of the same name.
     *
     * @param typeDef The type
     * @param context The method being written
     * @return The erased type
     * @since 2.3
     */
    public static Type getScopedType(TypeDef typeDef, MethodContext context) {
        return Type.getType(io.micronaut.sourcegen.bytecode.core.TypeUtils.getDescriptor(typeDef, context.objectDef(), context.methodDef(), context.enclosingScope()));
    }

    /**
     * The type of a type outside the write of an inner class: no variables of an enclosing class are in scope.
     *
     * @param typeDef   The type
     * @param objectDef The definition, if any
     * @return The erased type
     */
    public static Type getType(TypeDef typeDef, @Nullable ObjectDef objectDef) {
        return getType(typeDef, objectDef, EnclosingScope.NONE);
    }

    public static Type getType(TypeDef typeDef, @Nullable ObjectDef objectDef, EnclosingScope enclosingScope) {
        return Type.getType(io.micronaut.sourcegen.bytecode.core.TypeUtils.getDescriptor(typeDef, objectDef, enclosingScope));
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
