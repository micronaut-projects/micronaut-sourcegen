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
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves {@link Target} metadata for annotations represented by the Sourcegen model.
 *
 * <p>Annotation-processing class elements are checked before class loading because an annotation
 * type may be one of the source files currently being compiled.</p>
 *
 * @since 2.2
 */
@Internal
public final class AnnotationTargetUtils {

    private AnnotationTargetUtils() {
    }

    /**
     * @param annotation The annotation
     * @param classLoader The fallback class loader
     * @return Declared targets, or empty when the annotation has no usable target declaration
     */
    public static Optional<Set<ElementType>> targetsOf(AnnotationDef annotation,
                                                       @Nullable ClassLoader classLoader) {
        ClassTypeDef type = annotation.getType();
        if (type instanceof ClassTypeDef.ClassDefType classDefType) {
            return declaredTargetsOf(classDefType.objectDef());
        }
        if (type instanceof ClassTypeDef.ClassElementType classElementType) {
            Target target = sourceTargetOf(classElementType.classElement());
            if (target != null) {
                return Optional.of(Set.of(target.value()));
            }
        }
        Class<?> annotationType = resolveAnnotationType(type, classLoader);
        if (annotationType == null) {
            return Optional.empty();
        }
        Target target = annotationType.getAnnotation(Target.class);
        return target == null ? Optional.empty() : Optional.of(Set.of(target.value()));
    }

    /**
     * @param objectDef An annotation definition
     * @return Declared targets, or empty when none can be read
     */
    public static Optional<Set<ElementType>> declaredTargetsOf(ObjectDef objectDef) {
        return objectDef.getAnnotations().stream()
            .filter(annotation -> annotation.getType().getName().equals(Target.class.getName()))
            .findFirst()
            .map(annotation -> toElementTypes(annotation.getValues().get("value")))
            .filter(targets -> !targets.isEmpty());
    }

    /**
     * Converts the model's representation of a {@link Target#value()} member.
     *
     * @param value The member value
     * @return Element types
     */
    public static Set<ElementType> toElementTypes(@Nullable Object value) {
        if (value == null) {
            return Set.of();
        }
        if (value instanceof Collection<?> collection) {
            Set<ElementType> result = new LinkedHashSet<>();
            collection.forEach(item -> result.addAll(toElementTypes(item)));
            return result;
        }
        if (value instanceof Object[] array) {
            Set<ElementType> result = new LinkedHashSet<>();
            Arrays.stream(array).forEach(item -> result.addAll(toElementTypes(item)));
            return result;
        }
        if (value instanceof ElementType elementType) {
            return Set.of(elementType);
        }
        String name = value instanceof VariableDef.StaticField staticField ? staticField.name() : value.toString();
        try {
            return Set.of(ElementType.valueOf(name));
        } catch (IllegalArgumentException e) {
            return Set.of();
        }
    }

    @Nullable
    private static Target sourceTargetOf(ClassElement classElement) {
        Object nativeType = classElement.getNativeType();
        Element element = nativeType instanceof Element nativeElement ? nativeElement : unwrapNativeElement(nativeType);
        return element == null ? null : element.getAnnotation(Target.class);
    }

    @Nullable
    private static Element unwrapNativeElement(@Nullable Object nativeType) {
        if (nativeType == null) {
            return null;
        }
        try {
            Object element = nativeType.getClass().getMethod("element").invoke(nativeType);
            return element instanceof Element nativeElement ? nativeElement : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static Class<?> resolveAnnotationType(ClassTypeDef typeDef, @Nullable ClassLoader classLoader) {
        if (typeDef instanceof ClassTypeDef.JavaClass javaClass) {
            return javaClass.type();
        }
        try {
            return Class.forName(typeDef.getName(), false,
                classLoader == null ? AnnotationTargetUtils.class.getClassLoader() : classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
