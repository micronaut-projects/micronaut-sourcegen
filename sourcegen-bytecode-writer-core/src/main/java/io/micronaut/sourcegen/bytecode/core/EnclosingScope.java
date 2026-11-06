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
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeOperations;
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * The variables of the enclosing class in scope of the inner class a writer writes - a member class that is not
 * static - of the generated class the outer type names: {@code Supplier<T>} of an inner class of an {@code Outer<T>}
 * names the {@code T} of {@code Outer}. A writer creates one for each class it writes and passes it with the definition
 * to the descriptors and signatures it asks for.
 *
 * @param member    The inner class being written, or {@code null} for no scope
 * @param variables The variables of its enclosing class
 * @since 2.3
 */
@Internal
public record EnclosingScope(@Nullable ObjectDef member, List<TypeDef.TypeVariable> variables) {

    /**
     * No variables of an enclosing class: a top-level or static class, or a type described outside of a class being
     * written.
     */
    public static final EnclosingScope NONE = new EnclosingScope(null, List.of());

    /**
     * The scope a definition is written in.
     *
     * @param objectDef The definition being written
     * @param outerType The type enclosing it, if any
     * @return The scope, {@link #NONE} where the definition is not an inner class of a generated class
     */
    public static EnclosingScope of(ObjectDef objectDef, @Nullable ClassTypeDef outerType) {
        List<TypeDef.TypeVariable> variables = enclosingVariables(objectDef, outerType);
        return variables.isEmpty() ? NONE : new EnclosingScope(objectDef, variables);
    }

    /**
     * A variable of the enclosing class in the scope of a definition.
     *
     * @param objectDef The definition whose scope it is
     * @param name      The name of the variable
     * @return The variable, or {@code null} where the definition is not the inner class being written or its
     * enclosing class declares none of the name
     */
    public TypeDef.@Nullable TypeVariable variable(@Nullable ObjectDef objectDef, String name) {
        ObjectDef written = member;
        if (written == null || objectDef == null
            || (written != objectDef && !written.getName().equals(objectDef.getName()))) {
            return null;
        }
        return variables.stream().filter(variable -> variable.name().equals(name)).findFirst().orElse(null);
    }

    private static List<TypeDef.TypeVariable> enclosingVariables(ObjectDef member, @Nullable ClassTypeDef outerType) {
        // A generic definition names itself with its variables
        ClassTypeDef outerClass = outerType == null ? null : TypeOperations.rawClass(outerType);
        if (!(member instanceof ClassDef classDef) || classDef.getModifiers().contains(Modifier.STATIC)
            || !(outerClass instanceof ClassTypeDef.ClassDefType outer)) {
            return List.of();
        }
        // A member of an interface is static
        return switch (outer.objectDef()) {
            case ClassDef enclosing -> enclosing.getTypeVariables();
            case RecordDef enclosing -> enclosing.getTypeVariables();
            default -> List.of();
        };
    }
}
