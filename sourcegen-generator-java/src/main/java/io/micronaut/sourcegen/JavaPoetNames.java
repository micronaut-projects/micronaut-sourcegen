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
import io.micronaut.sourcegen.javapoet.ParameterizedTypeName;
import io.micronaut.sourcegen.javapoet.TypeName;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import io.micronaut.sourcegen.generator.GenerationScope;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * The names the Java source generator writes a type by, and the context it resolves them against.
 *
 * @since 2.3
 */
@Internal
final class JavaPoetNames {

    // The scope of the file being written: its definitions, and the context used to tell a nested class from a
    // generated top-level one by name
    private final GenerationScope scope;

    /**
     * @param scope The scope of the file being written
     */
    JavaPoetNames(GenerationScope scope) {
        this.scope = scope;
    }

    /**
     * Resolves a name known only as a string that contains a {@code $} past the first character of its
     * simple name. Such a name is ambiguous - {@code Outer$Inner} may be a nested class or a generated
     * top-level one such as {@code Foo$Intercepted} - so the compiler is asked.
     *
     * @param binaryName The binary name
     * @return The nested class name, or {@code null} when the name does not denote a nested class
     */
    private @Nullable ClassName resolveNestedClassName(String binaryName) {
        int simpleNameStart = binaryName.lastIndexOf('.') + 1;
        VisitorContext context = scope.visitorContext();
        if (context == null || binaryName.indexOf('$', simpleNameStart + 1) == -1) {
            return null;
        }
        return context.getClassElement(binaryName)
            .filter(ClassElement::isInner)
            .map(JavaPoetNames::asNestedClassName)
            .orElse(null);
    }

    private static ClassName asNestedClassName(ClassElement classElement) {
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
    private static ClassName asClassName(String name) {
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

    private static String packageNameOf(String binaryName) {
        int i = binaryName.lastIndexOf('.');
        return i == -1 ? "" : binaryName.substring(0, i);
    }

    private static String simpleNameOf(String binaryName) {
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

    boolean isVariablePartOfTheDefinition(String variableName,
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
                .anyMatch(tv -> tv.name().equals(variableName))
                // An inner class has the variables of its enclosing classes in scope
                || scope.enclosingVariables(classDef).stream().anyMatch(tv -> tv.name().equals(variableName));
            case InterfaceDef interfaceDef -> interfaceDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
            case RecordDef recordDef -> recordDef.getTypeVariables().stream()
                .anyMatch(tv -> tv.name().equals(variableName));
            case null, default -> false;
        };
    }

    /**
     * The method a lambda body is rendered as, which declares the type variables of the method the lambda is
     * written in: its body reads them, and their bounds convert the values it returns.
     */
    static MethodDef withTypeVariables(MethodDef implementation, @Nullable MethodDef enclosing) {
        // The body of a lambda written in a static method is in a static context, where no class variable is in scope
        boolean inStaticContext = enclosing != null && enclosing.getModifiers().contains(Modifier.STATIC)
            && !implementation.getModifiers().contains(Modifier.STATIC);
        if (enclosing == null || enclosing.getTypeVariables().isEmpty() && !inStaticContext) {
            return implementation;
        }
        MethodDef.MethodDefBuilder builder = MethodDef.builder(implementation.getName())
            .addModifiers(inStaticContext ? new Modifier[]{Modifier.STATIC} : new Modifier[0])
            .addModifiers(implementation.getModifiers())
            .returns(implementation.getReturnType())
            .addStatements(implementation.getStatements())
            .synthetic(implementation.isSynthetic());
        implementation.getParameters().forEach(builder::addParameter);
        implementation.getTypeVariables().forEach(builder::addTypeVariable);
        enclosing.getTypeVariables().stream()
            .filter(variable -> implementation.getTypeVariables().stream().noneMatch(own -> own.name().equals(variable.name())))
            .forEach(builder::addTypeVariable);
        return builder.build();
    }

    /**
     * A member of an enclosing type, {@code Outer<String>.Member<Integer>}: nested in the enclosing type where it is
     * parameterized.
     *
     * @param enclosing The enclosing type
     * @param member    The member class
     * @param arguments The type arguments of the member
     * @return The member type
     */
    TypeName memberType(TypeName enclosing, ClassTypeDef member, TypeName... arguments) {
        if (enclosing instanceof ParameterizedTypeName parameterized) {
            return parameterized.nestedClass(asClassType(member).simpleName(), List.of(arguments));
        }
        ClassName memberName = asClassType(member);
        return arguments.length == 0 ? memberName : ParameterizedTypeName.get(memberName, arguments);
    }

    /**
     * Converts a {@link ClassTypeDef} into a JavaPoet {@link ClassName}.
     *
     * <p>For an inner type the split is taken from the binary name ({@link ClassTypeDef#getName()}),
     * which is unambiguous; {@link ClassTypeDef#getCanonicalName()} cannot be used because it
     * rewrites {@code $} to {@code .}.
     *
     * @param classTypeDef The class type definition
     * @return The class name
     */
    ClassName asClassType(ClassTypeDef classTypeDef) {
        ClassName written = writtenClassName(classTypeDef.getName());
        if (written != null) {
            return written;
        }
        if (classTypeDef.isInner()) {
            String binaryName = classTypeDef.getName();
            // The separator is the first '$' of the simple name that is not its first character, so that
            // an enclosing type following the generated `$Foo` convention is not split in the middle
            int simpleNameStart = binaryName.lastIndexOf('.') + 1;
            int i = binaryName.indexOf('$', simpleNameStart + 1);
            if (i != -1) {
                String enclosing = binaryName.substring(0, i);
                String[] nested = binaryName.substring(i + 1).split("\\$", -1);
                return ClassName.get(packageNameOf(enclosing), simpleNameOf(enclosing), nested);
            }
        }
        ClassName nested = resolveNestedClassName(classTypeDef.getName());
        if (nested != null) {
            return nested;
        }
        return asClassName(classTypeDef.getCanonicalName());
    }

    /**
     * The name of a type nested in one of the file being written, by the simple names it is declared with: a `$` of
     * the binary name can be part of one - {@code Outer$Inner$Impl} is {@code Outer.Inner$Impl} where that is what the
     * definition declares.
     *
     * @param binaryName The binary name
     * @return The class name, or {@code null} where the name is no nested definition of the file being written
     */
    @Nullable
    ClassName writtenClassName(String binaryName) {
        if (binaryName.indexOf('$', binaryName.lastIndexOf('.') + 2) == -1 || !isWritten(binaryName)) {
            return null;
        }
        Deque<String> simpleNames = new ArrayDeque<>();
        String name = binaryName;
        String enclosing = enclosingWritten(name);
        while (enclosing != null) {
            simpleNames.addFirst(name.substring(enclosing.length() + 1));
            name = enclosing;
            enclosing = enclosingWritten(name);
        }
        return simpleNames.isEmpty() ? null : ClassName.get(packageNameOf(name), simpleNameOf(name), simpleNames.toArray(String[]::new));
    }

    /**
     * The simple name a definition is declared with: of a nested one, what its binary name adds to the one of the
     * definition enclosing it, which can contain a `$` - {@code Inner$Impl} of {@code Outer$Inner$Impl}.
     *
     * @param definition The definition
     * @return The simple name
     */
    String declaredSimpleName(ObjectDef definition) {
        String name = definition.asTypeDef().getName();
        String enclosing = enclosingWritten(name);
        return enclosing == null ? definition.getSimpleName() : name.substring(enclosing.length() + 1);
    }

    /**
     * @return The binary name of the definition of the file being written that encloses the named one, or {@code null}
     */
    @Nullable
    private String enclosingWritten(String binaryName) {
        int start = binaryName.lastIndexOf('.') + 1;
        for (int i = binaryName.lastIndexOf('$'); i > start; i = binaryName.lastIndexOf('$', i - 1)) {
            if (isWritten(binaryName.substring(0, i))) {
                return binaryName.substring(0, i);
            }
        }
        return null;
    }

    private boolean isWritten(String binaryName) {
        return scope.definitionOf(ClassTypeDef.of(binaryName)) != null;
    }

    boolean isRawGeneric(TypeDef type) {
        TypeDef unwrapped = TypeHierarchy.unwrap(type);
        if (unwrapped instanceof TypeDef.Array array) {
            return isRawGeneric(array.componentType());
        }
        if (unwrapped instanceof ClassTypeDef classType && !(unwrapped instanceof ClassTypeDef.Parameterized)
            && scope.definitionOf(classType) instanceof ObjectDef definition) {
            // A generated generic class named without its type arguments
            return definition instanceof ClassDef classDef && !classDef.getTypeVariables().isEmpty()
                || definition instanceof InterfaceDef interfaceDef && !interfaceDef.getTypeVariables().isEmpty()
                || definition instanceof RecordDef recordDef && !recordDef.getTypeVariables().isEmpty();
        }
        return unwrapped instanceof ClassTypeDef.JavaClass javaClass && javaClass.type().getTypeParameters().length > 0;
    }

}
