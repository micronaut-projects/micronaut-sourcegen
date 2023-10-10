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
 * The abstract element builder.
 *
 * @param <ThisType> The type of this builder
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public sealed class AbstractElementBuilder<ThisType> permits ClassDef.ClassDefBuilder, FieldDef.FieldDefBuilder, MethodDef.MethodDefBuilder {

    protected final String name;
    protected EnumSet<Modifier> modifiers = EnumSet.noneOf(Modifier.class);

    protected AbstractElementBuilder(String name) {
        this.name = name;
    }

    public final ThisType addModifiers(Modifier... modifiers) {
        this.modifiers.addAll(List.of(modifiers));
        return (ThisType) this;
    }

}
