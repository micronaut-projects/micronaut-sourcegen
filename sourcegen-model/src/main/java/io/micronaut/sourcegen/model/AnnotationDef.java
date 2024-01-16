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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Experimental;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The annotation definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class AnnotationDef {

    private final ClassTypeDef type;
    private final Map<String, Object> values;

    private AnnotationDef(ClassTypeDef type, Map<String, Object> values) {
        this.type = type;
        this.values = values;
    }

    public ClassTypeDef getType() {
        return type;
    }

    public Map<String, Object> getValues() {
        return values;
    }

    public static AnnotationDefBuilder builder(ClassTypeDef type) {
        return new AnnotationDefBuilder(type);
    }

    public static AnnotationDefBuilder builder(Class<? extends Annotation> annotationType) {
        return new AnnotationDefBuilder(ClassTypeDef.of(annotationType));
    }

    /**
     * Create an annotation definition from an {@link AnnotationValue}
     * annotation.
     * <p>Visitor context is required to deduce the types for
     * annotation members, as {@link AnnotationValue} does not retain
     * such information. The annotation does not need to be present on
     * the classpath, but type mirror information must be retrievable.</p>
     *
     * @param annotation The annotation
     * @param context The visitor context
     * @return The copy of given annotation
     *
     * @author Andriy Dmytruk
     * @since 1.0
     */
    public static AnnotationDef of(AnnotationValue<?> annotation, VisitorContext context) {
        ClassElement annotationElement = context.getClassElement(annotation.getAnnotationName())
            .orElseThrow(() -> new RuntimeException("Could not create class element for " + annotation.getAnnotationName()));
        Map<String, ClassElement> fieldTypes = annotationElement.getMethods().stream()
            .collect(Collectors.toMap(MethodElement::getName, MethodElement::getReturnType));

        String annotationTypeName = annotation.getAnnotationName().replace("$", ".");
        ClassTypeDef annotationType = ClassTypeDef.of(annotationTypeName);
        AnnotationDefBuilder builder = AnnotationDef.builder(annotationType);
        annotation.getConvertibleValues().asMap().forEach((key, value) ->
            copyAnnotationValue(value, fieldTypes.get(key), context)
                    .ifPresent(copiedValue -> builder.addMember(key, copiedValue))
        );
        return builder.build();
    }

    /**
     * A helper method for copying annotation members.
     *
     * @param value The member value to copy
     * @param requiredType The required type of the value
     * @param context The visitor context
     * @return The optional copied value
     */
    private static Optional<Object> copyAnnotationValue(
            Object value, ClassElement requiredType, VisitorContext context
    ) {
        // Annotation value must be one of: annotation, primitive, String, Class, array or enum.
        if (value instanceof Collection<?> collection) {
            // Special case: micronaut will store repeated annotations in a collection
            return Optional.of(collection.stream()
                .map(v -> copyAnnotationValue(v, requiredType, context))
                .filter(Optional::isPresent).map(Optional::get).toList());
        } else if (value instanceof AnnotationValue<?> annotationMember) {
            return Optional.of(of(annotationMember, context));
        } else if (requiredType.isArray()) {
            return Optional.of(streamOfArray(value)
                .map(v -> copyAnnotationValue(v, requiredType.fromArray(), context))
                .filter(Optional::isPresent).map(Optional::get).toList()
            );
        } else if (requiredType.isPrimitive() || requiredType.getName().equals("java.lang.String")) {
            return Optional.of(value);
        } else if (requiredType.getName().equals("java.lang.Class")) {
            return context.getClassElement(value.toString()).map(type ->
                new VariableDef.StaticField(TypeDef.of(type), "class", TypeDef.of(Class.class))
            );
        } else {
            System.err.println("BBB " + requiredType + " " + value);
            // Must be an enum
            TypeDef type = ClassTypeDef.of(requiredType);
            return Optional.of(new VariableDef.StaticField(type, value.toString(), type));
        }
    }

    /**
     * A helper method to create a stream from an array, since
     * Arrays.stream() will work only for Object[], and primitives need
     * to be converted first.
     *
     * @param value The array
     * @return The stream
     */
    private static Stream<?> streamOfArray(Object value) {
        if (value instanceof boolean[] values) {
            return IntStream.range(0, values.length).mapToObj(i -> values[i]);
        } else if (value instanceof byte[] values) {
            return IntStream.range(0, values.length).mapToObj(i -> values[i]);
        } else if (value instanceof char[] values) {
            return IntStream.range(0, values.length).mapToObj(i -> values[i]);
        } else if (value instanceof short[] values) {
            return IntStream.range(0, values.length).mapToObj(i -> values[i]);
        } else if (value instanceof int[] values) {
            return IntStream.range(0, values.length).mapToObj(i -> values[i]);
        } else if (value instanceof long[] values) {
            return IntStream.range(0, values.length).mapToObj(i -> values[i]);
        } else if (value instanceof float[] values) {
            return IntStream.range(0, values.length).mapToObj(i -> values[i]);
        } else if (value instanceof double[] values) {
            return IntStream.range(0, values.length).mapToObj(i -> values[i]);
        } else {
            return Arrays.stream((Object[]) value);
        }
    }

    /**
     * The annotation definition builder.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class AnnotationDefBuilder {

        private final ClassTypeDef type;
        private final Map<String, Object> values = new LinkedHashMap<>();

        public AnnotationDefBuilder(ClassTypeDef type) {
            this.type = type;
        }

        public AnnotationDefBuilder addMember(String member, Object value) {
            values.put(member, value);
            return this;
        }

        public AnnotationDefBuilder addMember(String member, Collection<Object> values) {
            this.values.put(member, values);
            return this;
        }

        public AnnotationDefBuilder addMember(String member, AnnotationDef annotationValue) {
            values.put(member, annotationValue);
            return this;
        }

        public AnnotationDef build() {
            return new AnnotationDef(type, Collections.unmodifiableMap(values));
        }
    }

}
