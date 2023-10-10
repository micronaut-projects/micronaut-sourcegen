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
import java.util.Collections;
import java.util.Set;

/**
 * The abstract element.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
abstract sealed class AbstractElement extends AbstractAnnotatedElement permits ClassDef, FieldDef, MethodDef {

    protected final String name;
    protected final Set<Modifier> modifiers;

    AbstractElement(String name, Set<Modifier> modifiers) {
        this.name = name;
        this.modifiers = Collections.unmodifiableSet(modifiers);
    }

    public final String getName() {
        return name;
    }

    public final Set<Modifier> getModifiers() {
        return modifiers;
    }

    public final Modifier[] getModifiersArray() {
        return modifiers.toArray(Modifier[]::new);
    }
}
