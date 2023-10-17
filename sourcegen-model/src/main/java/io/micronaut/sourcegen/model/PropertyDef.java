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
 * The property definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class PropertyDef extends AbstractElement {

    private final TypeDef type;

    private PropertyDef(String name, EnumSet<Modifier> modifiers, TypeDef type, List<AnnotationDef> annotations) {
        super(name, modifiers, annotations);
        if (type == null) {
            throw new IllegalStateException("The type of property: " + name + " is not specified!");
        }
        this.type = type;
    }

    public static PropertyDefBuilder builder(String name) {
        return new PropertyDefBuilder(name);
    }

    public TypeDef getType() {
        return type;
    }

    /**
     * The property builder definition.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class PropertyDefBuilder extends AbstractElementBuilder<PropertyDefBuilder> {

        private TypeDef type;

        private PropertyDefBuilder(String name) {
            super(name);
        }

        public PropertyDefBuilder ofType(TypeDef type) {
            this.type = type;
            return this;
        }

        public PropertyDefBuilder ofType(Class<?> type) {
            return ofType(TypeDef.of(type));
        }

        public PropertyDef build() {
            return new PropertyDef(name, modifiers, type, annotations);
        }

    }
}
