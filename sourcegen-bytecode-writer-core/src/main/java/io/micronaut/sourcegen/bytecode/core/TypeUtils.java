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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JVM descriptor operations shared by bytecode writers.
 *
 * <p>This class deliberately has no dependency on a bytecode library. Backends can turn the
 * descriptors into the representation their emitter needs.</p>
 *
 * @since 2.2
 */
@Internal
public final class TypeUtils {

    private static final Pattern ARRAY_PATTERN = Pattern.compile("(\\[])+$");

    private TypeUtils() {
    }

    /**
     * Returns the descriptor of a method.
     *
     * @param objectDef The contextual object, if any
     * @param methodDef The method
     * @return The JVM method descriptor
     */
    public static String getMethodDescriptor(@Nullable ObjectDef objectDef, MethodDef methodDef) {
        StringBuilder descriptor = new StringBuilder("(");
        for (ParameterDef parameter : methodDef.getParameters()) {
            descriptor.append(getDescriptor(parameter.getType(), objectDef));
        }
        return descriptor.append(')')
            .append(getDescriptor(Objects.requireNonNullElse(methodDef.getReturnType(), TypeDef.VOID), objectDef))
            .toString();
    }

    /**
     * Returns the erased JVM descriptor of a Sourcegen type.
     *
     * @param typeDef   The type
     * @param objectDef The contextual object, if any
     * @return The JVM descriptor
     */
    public static String getDescriptor(TypeDef typeDef, @Nullable ObjectDef objectDef) {
        typeDef = ObjectDef.getContextualType(objectDef, typeDef);
        if (typeDef instanceof TypeDef.AnnotatedTypeDef annotated) {
            return getDescriptor(annotated.typeDef(), objectDef);
        }
        if (typeDef instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return getDescriptor(annotated.typeDef(), objectDef);
        }
        if (typeDef instanceof TypeDef.Array array) {
            return "[".repeat(array.dimensions()) + getDescriptor(array.componentType(), objectDef);
        }
        if (typeDef instanceof ClassTypeDef.Parameterized parameterized) {
            return getDescriptor(parameterized.rawType(), objectDef);
        }
        if (typeDef instanceof ClassTypeDef classTypeDef) {
            return getDescriptor(getBinaryName(classTypeDef, objectDef));
        }
        if (typeDef instanceof TypeDef.Primitive primitive) {
            return primitiveDescriptor(primitive.name());
        }
        if (typeDef instanceof TypeDef.Wildcard wildcard) {
            List<TypeDef> bounds = !wildcard.lowerBounds().isEmpty()
                ? wildcard.lowerBounds()
                : wildcard.upperBounds();
            return getBoundsDescriptor(bounds, objectDef);
        }
        if (typeDef instanceof TypeDef.TypeVariable variable) {
            if (!variable.bounds().isEmpty()) {
                return getBoundsDescriptor(variable.bounds(), objectDef);
            }
            TypeDef.TypeVariable declaration = findTypeVariable(objectDef, variable.name());
            return declaration == null ? "Ljava/lang/Object;" : getBoundsDescriptor(declaration.bounds(), objectDef);
        }
        throw new IllegalStateException("Unsupported type: " + typeDef);
    }

    /**
     * The descriptor of the bound a variable erases to: the leftmost one, as javac erases it (JLS 4.6) - a variable
     * declared {@code T extends Object & Comparable<T>} erases to {@code Object}, so that the classes it is written
     * into link with the ones javac compiles from the same declaration.
     */
    private static String getBoundsDescriptor(List<TypeDef> bounds, @Nullable ObjectDef objectDef) {
        return bounds.isEmpty() ? "Ljava/lang/Object;" : getDescriptor(bounds.get(0), objectDef);
    }

    /**
     * Returns the binary name a reference to a type resolves to in the scope of the type being written.
     *
     * <p>The model is immutable, so {@link io.micronaut.sourcegen.model.ObjectDefBuilder#addInnerType} can
     * only qualify the copy of a member type it stores; the definition the caller holds on to keeps the
     * simple name it was built with, and a reference taken from it reads as {@code Inner} rather than
     * {@code com.example.Outer$Inner}. Java source has scoping that makes such a reference resolve anyway -
     * javac reads {@code Inner} inside {@code Outer} as its member type - while a class file has none: every
     * name in it is a binary name. This applies the same scoping the source generator relies on, so that
     * both generators accept the same definition.</p>
     *
     * <p>An unqualified name is resolved against the type being written and the member types it declares,
     * which is the scope a definition knows about. A name that is already qualified, and one that matches
     * nothing in scope, is left as it is.</p>
     *
     * @param classTypeDef The referenced type
     * @param objectDef    The contextual object, if any
     * @return The binary name to write
     */
    public static String getBinaryName(ClassTypeDef classTypeDef, @Nullable ObjectDef objectDef) {
        return TypeHierarchy.binaryName(classTypeDef, objectDef);
    }

    /**
     * Returns a descriptor for a binary class name or a source-style array name.
     *
     * @param className The class name
     * @return The descriptor
     */
    public static String getDescriptor(String className) {
        if (className.startsWith("[")) {
            // The binary name of an array class, as returned by Class#getName, is already a
            // descriptor: [Lcom.Example; or [I. Wrapping it again would produce L[Lcom/Example;;
            return className.replace('.', '/');
        }
        Matcher matcher = ARRAY_PATTERN.matcher(className);
        StringBuilder result = new StringBuilder();
        if (matcher.find()) {
            result.append("[".repeat(matcher.group(0).length() / 2));
            className = matcher.replaceFirst("");
        }
        result.append('L').append(getInternalName(className)).append(';');
        return result.toString();
    }

    /**
     * Returns the internal JVM name of a class.
     *
     * @param className The binary name
     * @return The internal name
     */
    public static String getInternalName(String className) {
        return ARRAY_PATTERN.matcher(className.replace('.', '/')).replaceFirst("");
    }

    private static String primitiveDescriptor(String name) {
        return switch (name) {
            case "void" -> "V";
            case "byte" -> "B";
            case "int" -> "I";
            case "boolean" -> "Z";
            case "long" -> "J";
            case "char" -> "C";
            case "short" -> "S";
            case "double" -> "D";
            case "float" -> "F";
            default -> throw new IllegalStateException("Expected a primitive type, got: " + name);
        };
    }

    private static TypeDef.@Nullable TypeVariable findTypeVariable(@Nullable ObjectDef objectDef, String name) {
        List<TypeDef.TypeVariable> variables = switch (objectDef) {
            case ClassDef classDef -> classDef.getTypeVariables();
            case InterfaceDef interfaceDef -> interfaceDef.getTypeVariables();
            case RecordDef recordDef -> recordDef.getTypeVariables();
            case null, default -> List.of();
        };
        return variables.stream()
            .filter(variable -> variable.name().equals(name))
            .findFirst()
            .orElse(null);
    }
}
