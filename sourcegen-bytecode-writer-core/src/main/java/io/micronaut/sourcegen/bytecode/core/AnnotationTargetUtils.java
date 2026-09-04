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
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Element;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
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
            .flatMap(annotation -> declaredTargets(annotation.getValues().get("value")));
    }

    /**
     * The targets a {@link Target} member names. An explicitly empty member targets nothing and drops the
     * annotation from every context, but nothing recognisable in a member that is not empty leaves the
     * annotation untargeted rather than targeting nothing at all, which would drop it everywhere.
     *
     * @param value The member of the {@link Target}
     * @return The targets it names, or empty when it names none that can be recognised
     */
    private static Optional<Set<ElementType>> declaredTargets(@Nullable Object value) {
        if (isEmptyMember(value)) {
            return Optional.of(Set.of());
        }
        Set<ElementType> targets = toElementTypes(value);
        return targets.isEmpty() ? Optional.empty() : Optional.of(targets);
    }

    private static boolean isEmptyMember(@Nullable Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        return value instanceof Object[] array && array.length == 0;
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

    /**
     * The annotations of a declaration that belong among its declaration annotations, which is every one of
     * them but those a compiler writes as type annotations instead. An annotation whose {@link Target} names
     * {@link ElementType#TYPE_USE} but not the declaration itself annotates the type of the declaration and
     * nothing else, the way {@code org.jspecify.annotations.Nullable} does; one that names both is written
     * in both places, and one that names neither is left where it was put rather than dropped.
     *
     * @param annotations The annotations of the declaration
     * @param elementType The kind of declaration they are written on
     * @param classLoader The fallback class loader
     * @return The annotations that belong among the declaration annotations
     */
    public static List<AnnotationDef> declarationAnnotations(List<AnnotationDef> annotations,
                                                             ElementType elementType,
                                                             @Nullable ClassLoader classLoader) {
        return annotations.stream()
            .filter(annotation -> targetsOf(annotation, classLoader)
                .map(targets -> targets.contains(elementType) || !targets.contains(ElementType.TYPE_USE))
                .orElse(true))
            .toList();
    }

    /**
     * @param annotations The annotations of a declaration
     * @param classLoader The fallback class loader
     * @return Those of them that annotate a type use
     */
    public static List<AnnotationDef> typeUseAnnotations(List<AnnotationDef> annotations,
                                                         @Nullable ClassLoader classLoader) {
        return annotations.stream()
            .filter(annotation -> targetsOf(annotation, classLoader)
                .map(targets -> targets.contains(ElementType.TYPE_USE))
                .orElse(false))
            .toList();
    }

    /**
     * The type of a declaration, carrying those of the declaration's annotations that annotate a type use. A
     * compiler writes such an annotation against the type of the declaration and not against the declaration
     * itself, so that a reader finds it on the annotated type.
     *
     * @param typeDef     The declared type
     * @param annotations The annotations of the declaration
     * @param classLoader The fallback class loader
     * @return The type, annotated when any of them belongs on it
     */
    public static TypeDef annotatedType(TypeDef typeDef,
                                        List<AnnotationDef> annotations,
                                        @Nullable ClassLoader classLoader) {
        List<AnnotationDef> typeUse = typeUseAnnotations(annotations, classLoader);
        return typeUse.isEmpty() ? typeDef : annotateDeclaredType(typeDef, typeUse);
    }

    /**
     * An annotation written before the type of a declaration annotates what an array holds and not the array
     * itself - {@code @Nullable String[]} is an array of annotated strings (JLS 9.7.4), which is how a
     * compiler reads it and how the source generators render it. Any other type is annotated as it stands.
     *
     * @param typeDef     The declared type
     * @param annotations The type use annotations of the declaration
     * @return The annotated type
     */
    private static TypeDef annotateDeclaredType(TypeDef typeDef, List<AnnotationDef> annotations) {
        // An annotation already on the type wraps it, and only this wrapper can be hiding an array - the one
        // for a class type cannot. Descend through it and put it back, so what it annotates stays the same
        if (typeDef instanceof TypeDef.AnnotatedTypeDef annotated) {
            return new TypeDef.AnnotatedTypeDef(
                annotateDeclaredType(annotated.typeDef(), annotations),
                annotated.annotations()
            );
        }
        if (typeDef instanceof ClassTypeDef.AnnotatedClassTypeDef annotated) {
            return new ClassTypeDef.AnnotatedClassTypeDef(
                (ClassTypeDef) annotateDeclaredType(annotated.typeDef(), annotations),
                annotated.annotations()
            );
        }
        if (typeDef instanceof TypeDef.Array array) {
            return new TypeDef.Array(
                annotateDeclaredType(array.componentType(), annotations),
                array.dimensions(),
                array.nullable()
            );
        }
        return typeDef.annotated(annotations);
    }

    /**
     * The retention of an annotation, resolved the way the ASM backend resolves it.
     *
     * @param annotation The annotation
     * @param classLoader The fallback class loader
     * @return Its retention
     */
    public static RetentionPolicy retentionOf(AnnotationDef annotation, @Nullable ClassLoader classLoader) {
        ClassTypeDef type = annotation.getType();
        if (type instanceof ClassTypeDef.ClassDefType classDefType) {
            return declaredRetentionOf(classDefType.objectDef()).orElse(RetentionPolicy.CLASS);
        }
        if (type instanceof ClassTypeDef.ClassElementType classElementType) {
            Retention retention = sourceAnnotation(classElementType.classElement(), Retention.class);
            if (retention != null) {
                return retention.value();
            }
        }
        Class<?> annotationType = resolveAnnotationType(type, classLoader);
        if (annotationType == null) {
            // Not on the generator's class path, which is the normal case for a processor writing
            // annotations of the project it is processing. Keep the runtime visibility they had
            // before retention was honoured at all rather than hiding them from their readers.
            return type instanceof ClassTypeDef.ClassElementType ? RetentionPolicy.CLASS : RetentionPolicy.RUNTIME;
        }
        Retention retention = annotationType.getAnnotation(Retention.class);
        return retention == null ? RetentionPolicy.CLASS : retention.value();
    }

    /**
     * @param objectDef An annotation definition
     * @return The retention it declares, or empty when it declares none
     */
    public static Optional<RetentionPolicy> declaredRetentionOf(ObjectDef objectDef) {
        return objectDef.getAnnotations().stream()
            .filter(annotation -> annotation.getType().getName().equals(Retention.class.getName()))
            .findFirst()
            .flatMap(annotation -> toRetentionPolicy(annotation.getValues().get("value")));
    }

    private static Optional<RetentionPolicy> toRetentionPolicy(@Nullable Object value) {
        if (value instanceof RetentionPolicy policy) {
            return Optional.of(policy);
        }
        String name = value instanceof VariableDef.StaticField staticField
            ? staticField.name() : java.util.Objects.toString(value, "");
        try {
            return Optional.of(RetentionPolicy.valueOf(name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a member of an annotation is declared with an array type.
     *
     * <p>A single value is a legal source form for an array member - {@code @Target(TYPE)} means
     * {@code @Target({TYPE})} - but a class file has no such shorthand, so a writer has to know the
     * declared type to wrap the value. The declaration is read from the definition of an annotation
     * type generated in this same round, from the source element of one still being compiled, and
     * otherwise from the loaded class.</p>
     *
     * @param annotation  The annotation
     * @param member      The name of the member
     * @param classLoader The fallback class loader
     * @return Whether the member is declared as an array
     */
    public static boolean isArrayMember(AnnotationDef annotation, String member, @Nullable ClassLoader classLoader) {
        ClassTypeDef type = annotation.getType();
        if (type instanceof ClassTypeDef.ClassDefType classDefType) {
            return classDefType.objectDef() instanceof AnnotationObjectDef annotationObjectDef
                && annotationObjectDef.getMembers().stream()
                .filter(memberDef -> memberDef.getName().equals(member))
                .anyMatch(memberDef -> memberDef.getType() instanceof TypeDef.Array);
        }
        if (type instanceof ClassTypeDef.ClassElementType classElementType) {
            return isSourceArrayMember(classElementType.classElement(), member);
        }
        Class<?> annotationType = resolveAnnotationType(type, classLoader);
        if (annotationType == null) {
            return false;
        }
        try {
            return annotationType.getMethod(member).getReturnType().isArray();
        } catch (NoSuchMethodException | LinkageError _) {
            return false;
        }
    }

    private static boolean isSourceArrayMember(ClassElement classElement, String member) {
        try {
            return classElement.getEnclosedElements(ElementQuery.ALL_METHODS).stream()
                .filter(method -> method.getName().equals(member))
                .map(MethodElement::getReturnType)
                .anyMatch(TypedElement::isArray);
        } catch (RuntimeException _) {
            return false;
        }
    }

    @Nullable
    private static Target sourceTargetOf(ClassElement classElement) {
        return sourceAnnotation(classElement, Target.class);
    }

    @Nullable
    private static <A extends java.lang.annotation.Annotation> A sourceAnnotation(ClassElement classElement,
                                                                                  Class<A> annotationType) {
        Object nativeType = classElement.getNativeType();
        Element element = nativeType instanceof Element nativeElement ? nativeElement : unwrapNativeElement(nativeType);
        return element == null ? null : element.getAnnotation(annotationType);
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
