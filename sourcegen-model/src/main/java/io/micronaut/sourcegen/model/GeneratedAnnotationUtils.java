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
import io.micronaut.sourcegen.annotations.GeneratedSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Supplies the retained generated marker when building type definitions.
 */
@Internal
final class GeneratedAnnotationUtils {
    private static final AnnotationDef GENERATED_SOURCE = AnnotationDef.builder(GeneratedSource.class).build();

    private GeneratedAnnotationUtils() {
    }

    /**
     * Returns type annotations including exactly one automatically supplied marker.
     *
     * @param annotations The annotations supplied to a type builder
     * @return The original annotations, with the marker appended if absent
     */
    static List<AnnotationDef> getAnnotations(List<AnnotationDef> annotations) {
        if (annotations.stream().anyMatch(annotation -> annotation.getType().getName().equals(GeneratedSource.class.getName()))) {
            return List.copyOf(annotations);
        }
        List<AnnotationDef> generated = new ArrayList<>(annotations);
        generated.add(GENERATED_SOURCE);
        return List.copyOf(generated);
    }
}
