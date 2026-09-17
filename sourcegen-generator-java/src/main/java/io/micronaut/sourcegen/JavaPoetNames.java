/*
 * Copyright 2017-2023 original authors
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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.javapoet.ClassName;
import io.micronaut.sourcegen.javapoet.TypeName;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * The names the Java source generator writes a type by, and the context it resolves them against.
 *
 * @since 2.2
 */
@Internal
final class JavaPoetNames {

    // The context of the file being written, used to tell a nested class from a generated top-level one by name and
    // to look up the supertypes of an override that are only known by name
    private static final ThreadLocal<VisitorContext> VISITOR_CONTEXT = new ThreadLocal<>();

    private JavaPoetNames() {
    }

    /**
     * @return The context of the file being written, or {@code null} outside of writing one
     */
    @Nullable
    static VisitorContext context() {
        return VISITOR_CONTEXT.get();
    }

    /**
     * @param context The context of the file about to be written
     * @return The context to restore afterwards
     */
    @Nullable
    static VisitorContext enter(VisitorContext context) {
        VisitorContext previous = VISITOR_CONTEXT.get();
        VISITOR_CONTEXT.set(context);
        return previous;
    }

    /**
     * @param previous The context returned by {@link #enter(VisitorContext)}
     */
    static void exit(@Nullable VisitorContext previous) {
        if (previous == null) {
            VISITOR_CONTEXT.remove();
        } else {
            VISITOR_CONTEXT.set(previous);
        }
    }

    /**
     * Resolves a name known only as a string that contains a {@code $} past the first character of its
     * simple name. Such a name is ambiguous - {@code Outer$Inner} may be a nested class or a generated
     * top-level one such as {@code Foo$Intercepted} - so the compiler is asked.
     *
     * @param binaryName The binary name
     * @return The nested class name, or {@code null} when the name does not denote a nested class
     */
    static @Nullable ClassName resolveNestedClassName(String binaryName) {
        int simpleNameStart = binaryName.lastIndexOf('.') + 1;
        VisitorContext context = VISITOR_CONTEXT.get();
        if (context == null || binaryName.indexOf('$', simpleNameStart + 1) == -1) {
            return null;
        }
        return context.getClassElement(binaryName)
            .filter(ClassElement::isInner)
            .map(JavaPoetNames::asNestedClassName)
            .orElse(null);
    }

    static ClassName asNestedClassName(ClassElement classElement) {
        Deque<String> simpleNames = new ArrayDeque<>();
        ClassElement current = classElement;
        ClassElement enclosing = current.getEnclosingType().orElse(null);
        while (enclosing != null) {
            // The element's own simple name can keep the `Outer$` prefix; take what follows the enclosing binary name
            simpleNames.addFirst(current.getName().substring(enclosing.getName().length() + 1));
            current = enclosing;
            enclosing = current.getEnclosingType().orElse(null);
        }
        return ClassName.get(current.getPackageName(), simpleNameOf(current.getName()), simpleNames.toArray(String[]::new));
    }

    /**
     * A lenient variant of {@link ClassName#bestGuess(String)}.
     *
     * <p>It infers the package the same way - by consuming the leading lower-case segments - but it
     * does not require the remaining simple names to start with an upper-case letter, so generated
     * names following the {@code $Foo$Bar} convention are supported, and it does not fail when the
     * name has no package at all.
     *
     * @param name The fully qualified name
     * @return The class name
     */
    static ClassName asClassName(String name) {
        int p = 0;
        while (p < name.length() && Character.isLowerCase(name.codePointAt(p))) {
            int dot = name.indexOf('.', p);
            if (dot == -1) {
                break;
            }
            p = dot + 1;
        }
        String packageName = p == 0 ? "" : name.substring(0, p - 1);
        String[] simpleNames = name.substring(p).split("\\.", -1);
        return ClassName.get(
            packageName,
            simpleNames[0],
            Arrays.copyOfRange(simpleNames, 1, simpleNames.length)
        );
    }

    static String packageNameOf(String binaryName) {
        int i = binaryName.lastIndexOf('.');
        return i == -1 ? "" : binaryName.substring(0, i);
    }

    static String simpleNameOf(String binaryName) {
        int i = binaryName.lastIndexOf('.');
        return i == -1 ? binaryName : binaryName.substring(i + 1);
    }

    static TypeName asPrimitiveType(TypeDef.Primitive primitive) {
        return switch (primitive.name()) {
            case "void" -> TypeName.VOID;
            case "byte" -> TypeName.BYTE;
            case "short" -> TypeName.SHORT;
            case "char" -> TypeName.CHAR;
            case "int" -> TypeName.INT;
            case "long" -> TypeName.LONG;
            case "float" -> TypeName.FLOAT;
            case "double" -> TypeName.DOUBLE;
            case "boolean" -> TypeName.BOOLEAN;
            default -> throw new IllegalStateException("Unrecognized primitive name: " + primitive.name());
        };
    }

    static boolean isVariablePartOfTheDefinition(String variableName,
                                                         @Nullable ObjectDef objectDef,
                                                         @Nullable MethodDef methodDef,
                                                         boolean staticContext) {
        if (methodDef != null
            && methodDef.getTypeVariables().stream().anyMatch(v -> v.name().equals(variableName))) {
            return true;
        }
        if (staticContext) {
            return false;
        }
        return switch (objectDef) {
            case ClassDef classDef -> classDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
            case InterfaceDef interfaceDef -> interfaceDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
            case RecordDef recordDef -> recordDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
            case null, default -> false;
        };
    }
}
