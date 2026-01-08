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
import org.jspecify.annotations.NonNull;

import javax.lang.model.element.Modifier;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The field definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class FieldDef extends AbstractElement {

    private final TypeDef type;
    private final ExpressionDef initializer;

    private FieldDef(String name,
                     EnumSet<Modifier> modifiers,
                     TypeDef type,
                     ExpressionDef initializer,
                     List<AnnotationDef> annotations,
                     List<String> javadoc,
                     boolean synthetic) {
        super(name, modifiers, annotations, javadoc, synthetic);
        this.type = type;
        this.initializer = initializer;
    }

    /**
     * Creates a new field builder with a name.
     * @param name The filed name
     * @return The field builder
     * @since 1.5
     */
    @NonNull
    public static FieldDefBuilder builder(@NonNull String name) {
        return new FieldDefBuilder(name);
    }

    /**
     * Creates a new field builder with a name and type.
     *
     * @param name The filed name
     * @param type The type
     * @return The field builder
     * @since 1.5
     */
    @NonNull
    public static FieldDefBuilder builder(@NonNull String name, @NonNull TypeDef type) {
        return new FieldDefBuilder(name, type);
    }

    /**
     * Creates a new field builder with a name and type.
     * @param name The filed name
     * @param type The type
     * @return The field builder
     * @since 1.5
     */
    @NonNull
    public static FieldDefBuilder builder(@NonNull String name, @NonNull Class<?> type) {
        return new FieldDefBuilder(name, TypeDef.of(type));
    }

    public TypeDef getType() {
        return type;
    }

    public Optional<ExpressionDef> getInitializer() {
        return Optional.ofNullable(initializer);
    }

    /**
     * The field builder definition.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class FieldDefBuilder extends AbstractElementBuilder<FieldDefBuilder> {

        private TypeDef type;
        private ExpressionDef initializer;

        private FieldDefBuilder(String name) {
            super(name);
        }

        private FieldDefBuilder(String name, TypeDef type) {
            this(name);
            this.type = type;
        }

        public FieldDefBuilder ofType(TypeDef type) {
            this.type = type;
            return this;
        }

        public FieldDef build() {
            Objects.requireNonNull(name, "Name cannot be null");
            Objects.requireNonNull(type, "Type cannot be null");
            return new FieldDef(name, modifiers, type, initializer, annotations, javadoc, synthetic);
        }

        public FieldDefBuilder initializer(ExpressionDef expr) {
            this.initializer = expr;
            return this;
        }
    }
}
