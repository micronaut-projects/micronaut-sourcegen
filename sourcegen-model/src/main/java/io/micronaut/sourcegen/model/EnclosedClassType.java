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
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * A member class carrying an enclosing type that its own model does not have: {@code Outer<T>.Member} with
 * {@code T} substituted, or {@code Outer<String>.Member} converted from reflection. A member of a parameterized
 * type named by a compiler element is its {@link ClassTypeDef.ClassElementType}, whose enclosing type is read from
 * the element ({@link TypeHierarchy#enclosingOf(ClassTypeDef)}).
 *
 * <p>Not part of the public model: {@link TypeHierarchy#memberType(ClassTypeDef, ClassTypeDef)} creates it, and
 * {@link TypeHierarchy#enclosingOf(ClassTypeDef)} and {@link TypeHierarchy#memberClass(ClassTypeDef)} take it apart.
 * Otherwise it is its member class: it has its name, and its declared methods.</p>
 *
 * @param enclosing The enclosing type, with its type arguments
 * @param member    The member class
 * @since 2.3
 */
@Internal
record EnclosedClassType(ClassTypeDef enclosing, ClassTypeDef member) implements ClassTypeDef {

    @Override
    public EnclosedClassType resolveTypeVariables(Function<String, @Nullable TypeDef> resolveVariableFn) {
        return new EnclosedClassType(enclosing.resolveTypeVariables(resolveVariableFn), member);
    }

    @Override
    public List<String> getTypeVariableNames() {
        return member.getTypeVariableNames();
    }

    @Override
    public LambdaDef getLambda(Function<String, @Nullable TypeDef> resolveVariableFn) {
        return member.getLambda(resolveVariableFn);
    }

    @Override
    public List<MethodDef> findDeclaredMethods(String name, int argumentCount) {
        return member.findDeclaredMethods(name, argumentCount);
    }

    @Override
    public String getName() {
        return member.getName();
    }

    @Override
    public String getSimpleName() {
        return member.getSimpleName();
    }

    @Override
    public String getCanonicalName() {
        return member.getCanonicalName();
    }

    @Override
    public boolean isNullable() {
        return member.isNullable();
    }

    @Override
    public ClassTypeDef makeNullable() {
        return new EnclosedClassType(enclosing, member.makeNullable());
    }

    @Override
    public boolean isInner() {
        return true;
    }

    @Override
    public boolean isInterface() {
        return member.isInterface();
    }

    @Override
    public int hashCode() {
        return getName().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof ClassTypeDef other && !(other instanceof ClassTypeDef.Parameterized)
            && getName().equals(other.getName()) && isNullable() == other.isNullable()
            && enclosing.equals(TypeHierarchy.enclosingOf(other));
    }
}
