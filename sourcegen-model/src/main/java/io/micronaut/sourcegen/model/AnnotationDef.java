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

import io.micronaut.core.annotation.Experimental;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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

        public AnnotationDef build() {
            return new AnnotationDef(type, Collections.unmodifiableMap(values));
        }
    }

}
