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

import io.micronaut.core.annotation.Internal;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Objects;

/**
 * Validation shared by the {@link MethodReferenceExpression} forms.
 *
 * @author Denis Stepanov
 * @since 2.2
 */
@Internal
final class MethodReferences {

    private MethodReferences() {
    }

    /**
     * Resolve the one method of a type that a by-name reference targets.
     *
     * <p>Several methods can share a name and arity, so this is only for the conveniences that take a
     * name; it is deliberately not public API.
     *
     * @param owner         The type declaring the method
     * @param name          The method name, or {@link MethodDef#CONSTRUCTOR}
     * @param argumentCount The number of arguments the method takes
     * @return The single matching method
     */
    static MethodDef resolve(ClassTypeDef owner, String name, int argumentCount) {
        List<MethodDef> candidates = owner.findDeclaredMethods(name, argumentCount);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No method " + owner.getName() + "#" + name
                + " accepting " + argumentCount + " argument(s) found");
        }
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("Ambiguous method reference: " + candidates.size()
                + " methods named " + owner.getName() + "#" + name + " accept " + argumentCount + " argument(s)");
        }
        return candidates.get(0);
    }

    /**
     * Validates that the referenced method fills the shape of the functional interface.
     *
     * @param type             The functional interface
     * @param target           The method declared by the interface
     * @param instantiated     The interface method with its type variables resolved
     * @param owner            The type declaring the referenced method
     * @param method           The referenced method
     * @param staticReference  Whether the method is called statically
     */
    static void validate(ClassTypeDef type,
                         MethodDef target,
                         MethodDef instantiated,
                         ClassTypeDef owner,
                         MethodDef method,
                         boolean staticReference) {
        Objects.requireNonNull(type, "The functional interface type cannot be null");
        Objects.requireNonNull(target, "The functional interface method cannot be null");
        Objects.requireNonNull(instantiated, "The instantiated functional interface method cannot be null");
        Objects.requireNonNull(owner, "The owner type cannot be null");
        Objects.requireNonNull(method, "The referenced method cannot be null");
        // A MethodDef does not always record that its method is static, so this catches only the
        // mismatches it can see rather than standing in for the form of the reference
        if (!staticReference && method.getModifiers().contains(Modifier.STATIC)) {
            throw new IllegalArgumentException("Static method " + owner.getName() + "#" + method.getName()
                + " cannot be referenced on an instance");
        }
        int expected = method.getParameters().size();
        if (expected != instantiated.getParameters().size()) {
            throw new IllegalArgumentException("Method reference " + owner.getName() + "#" + method.getName()
                + " accepts " + expected + " argument(s) but " + type.getName() + "#" + instantiated.getName()
                + " provides " + instantiated.getParameters().size());
        }
    }

}
