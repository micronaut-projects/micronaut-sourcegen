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
package io.micronaut.sourcegen.generator;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.TypeOperations;
import io.micronaut.sourcegen.model.TypeTransformer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Renames the type variables of a method in its body: an override whose variable would capture a variable of the
 * declaring type of the same name, which a resolved signature names, declares it by another name.
 *
 * <p>The body is rewritten by a {@link TypeTransformer}: a method it invokes is left as it is - its types are in the
 * scope of its declaration - while the implementation of a lambda is in the scope of the method, and renamed.</p>
 *
 * @since 2.3
 */
@Internal
final class TypeVariableRenaming implements UnaryOperator<TypeDef> {

    private final Map<String, String> renamed;
    private final Map<String, TypeDef> references = new HashMap<>();

    private TypeVariableRenaming(Map<String, String> renamed) {
        this.renamed = renamed;
        renamed.forEach((name, newName) -> references.put(name, TypeDef.variable(newName)));
    }

    /**
     * @param type    A type in the scope of the method
     * @param renamed The new names of the renamed variables
     * @return The type with the variables renamed
     */
    static TypeDef rename(TypeDef type, Map<String, String> renamed) {
        return new TypeVariableRenaming(renamed).apply(type);
    }

    /**
     * @param statements The statements of the method
     * @param renamed    The new names of the renamed variables
     * @return The statements with the variables renamed in each type they name
     */
    static List<StatementDef> rename(List<StatementDef> statements, Map<String, String> renamed) {
        return TypeTransformer.of(new TypeVariableRenaming(renamed)).statements(statements);
    }

    @Override
    public TypeDef apply(TypeDef type) {
        if (!TypeOperations.mentionsVariable(type, renamed::containsKey)) {
            return type;
        }
        return switch (type) {
            case TypeDef.TypeVariable variable -> new TypeDef.TypeVariable(renamed.getOrDefault(variable.name(), variable.name()),
                variable.bounds().stream().map(this).toList(), variable.isNullable());
            case TypeDef.AnnotatedTypeDef annotated -> new TypeDef.AnnotatedTypeDef(apply(annotated.typeDef()), annotated.annotations());
            case ClassTypeDef.AnnotatedClassTypeDef annotated -> apply(annotated.typeDef()) instanceof ClassTypeDef classType
                ? new ClassTypeDef.AnnotatedClassTypeDef(classType, annotated.annotations()) : type;
            default -> TypeOperations.substitute(type, references);
        };
    }
}
