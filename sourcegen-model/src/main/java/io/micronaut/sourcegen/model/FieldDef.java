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

import javax.lang.model.element.Modifier;
import java.util.EnumSet;
import java.util.List;

/**
 * The field definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class FieldDef extends AbstractElement {

    private final TypeDef type;

    private FieldDef(String name,
                     EnumSet<Modifier> modifiers,
                     TypeDef type,
                     List<AnnotationDef> annotations,
                     List<String> javadoc) {
        super(name, modifiers, annotations, javadoc);
        this.type = type;
    }

    public static FieldDefBuilder builder(String name) {
        return new FieldDefBuilder(name);
    }

    public TypeDef getType() {
        return type;
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

        private FieldDefBuilder(String name) {
            super(name);
        }

        public FieldDefBuilder ofType(TypeDef type) {
            this.type = type;
            return this;
        }

        public FieldDef build() {
            return new FieldDef(name, modifiers, type, annotations, javadoc);
        }

    }
}
