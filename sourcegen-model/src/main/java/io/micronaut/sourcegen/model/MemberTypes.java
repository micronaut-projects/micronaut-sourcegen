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
import io.micronaut.inject.ast.ClassElement;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The enclosing type of a member class, as a Java element names it: {@code Outer<String>.Member}. The element model
 * of a class does not have the type arguments of its enclosing type, the type mirror javac gives it does.
 *
 * @since 2.3
 */
@Internal
final class MemberTypes {

    /**
     * The accessor of the type mirror, per native element class.
     */
    private static final ClassValue<@Nullable Method> TYPE_MIRROR = new ClassValue<>() {
        @Override
        protected @Nullable Method computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("typeMirror");
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    };

    private MemberTypes() {
    }

    /**
     * The enclosing type of a class element, where it is a parameterized type - {@code Outer<String>} of
     * {@code Outer<String>.Member}.
     *
     * @param classElement The class element
     * @return The enclosing type, or null where the class is not a member of a parameterized type
     */
    static @Nullable ClassTypeDef enclosingOf(ClassElement classElement) {
        // Only an inner class has an enclosing instance type; the element tells without the type mirror
        if (!classElement.isInner() || classElement.isArray()
            || !(typeMirror(classElement.getNativeType()) instanceof DeclaredType declared)) {
            return null;
        }
        if (declared.getEnclosingType() instanceof DeclaredType enclosing && hasTypeArguments(enclosing)) {
            return (ClassTypeDef) of(enclosing);
        }
        return null;
    }

    private static boolean hasTypeArguments(DeclaredType type) {
        return !type.getTypeArguments().isEmpty()
            || type.getEnclosingType() instanceof DeclaredType enclosing && hasTypeArguments(enclosing);
    }

    /**
     * The type mirror of a native Java element, {@code JavaNativeElement.Class#typeMirror()}, which this module
     * does not depend on.
     */
    private static @Nullable TypeMirror typeMirror(@Nullable Object nativeType) {
        if (nativeType == null) {
            return null;
        }
        Method method = TYPE_MIRROR.get(nativeType.getClass());
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(nativeType) instanceof TypeMirror typeMirror ? typeMirror : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static TypeDef of(TypeMirror type) {
        if (type instanceof PrimitiveType) {
            return TypeDef.primitive(type.getKind().name().toLowerCase(Locale.ROOT));
        }
        if (type instanceof ArrayType array) {
            return of(array.getComponentType()).array();
        }
        if (type instanceof javax.lang.model.type.TypeVariable variable) {
            return TypeDef.variable(variable.asElement().getSimpleName().toString());
        }
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getSuperBound() != null) {
                return TypeDef.wildcardSupertypeOf(of(wildcard.getSuperBound()));
            }
            if (wildcard.getExtendsBound() != null) {
                return TypeDef.wildcardSubtypeOf(of(wildcard.getExtendsBound()));
            }
            return TypeDef.wildcard();
        }
        if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            ClassTypeDef rawType = ClassTypeDef.of(binaryName(element), element.getEnclosingElement() instanceof TypeElement);
            if (declared.getEnclosingType() instanceof DeclaredType enclosing && hasTypeArguments(enclosing)) {
                rawType = TypeHierarchy.memberType((ClassTypeDef) of(enclosing), rawType);
            }
            if (declared.getTypeArguments().isEmpty()) {
                return rawType;
            }
            List<TypeDef> arguments = new ArrayList<>();
            declared.getTypeArguments().forEach(argument -> arguments.add(of(argument)));
            return new ClassTypeDef.Parameterized(rawType, arguments);
        }
        return type.getKind() == TypeKind.VOID ? TypeDef.VOID : TypeDef.OBJECT;
    }

    private static String binaryName(TypeElement element) {
        Element enclosing = element.getEnclosingElement();
        if (enclosing instanceof TypeElement enclosingType) {
            return binaryName(enclosingType) + "$" + element.getSimpleName();
        }
        return element.getQualifiedName().toString();
    }
}
