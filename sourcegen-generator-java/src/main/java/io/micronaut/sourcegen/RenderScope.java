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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The naming scope of a method or lambda body being rendered.
 *
 * <p>A lambda body is rendered in a scope of its own, nested in the scope of the enclosing
 * method, so that a name that is already in scope can be detected and a lambda parameter can be
 * renamed to avoid shadowing it - Java forbids a lambda parameter from shadowing a name in
 * scope - and so that a reference to an enclosing method's parameter resolves instead of
 * failing.
 */
@Internal
final class RenderScope {

    @Nullable
    private final RenderScope parent;
    @Nullable
    private final MethodDef owner;
    private final Map<String, String> renames = new LinkedHashMap<>();
    private final Set<String> taken = new LinkedHashSet<>();

    private RenderScope(@Nullable RenderScope parent, @Nullable MethodDef owner) {
        this.parent = parent;
        this.owner = owner;
        if (owner != null) {
            for (ParameterDef parameter : owner.getParameters()) {
                taken.add(parameter.getName());
            }
        }
    }

    /**
     * @param owner The method the scope belongs to
     * @return A root scope
     */
    static RenderScope root(@Nullable MethodDef owner) {
        return new RenderScope(null, owner);
    }

    /**
     * @param owner The method the nested scope belongs to
     * @return A scope nested in this one
     */
    RenderScope nested(@Nullable MethodDef owner) {
        return new RenderScope(this, owner);
    }

    /**
     * Records a name as declared in this scope, so that a nested lambda does not reuse it.
     *
     * @param name The name
     */
    void declare(String name) {
        taken.add(name);
    }

    /**
     * Records that a name of the owning method is emitted under a different name.
     *
     * @param name        The name in the model
     * @param emittedName The name to emit
     */
    void rename(String name, String emittedName) {
        renames.put(name, emittedName);
        taken.add(emittedName);
    }

    /**
     * @param name The name
     * @return True if the name is already used by this scope or any enclosing one
     */
    boolean isTaken(String name) {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.taken.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Allocates a name that is not used by this scope or any enclosing one.
     *
     * @param name The preferred name
     * @return The preferred name, or a name derived from it
     */
    String allocate(String name) {
        if (!isTaken(name)) {
            return name;
        }
        int i = 1;
        String candidate = name + i;
        while (isTaken(candidate)) {
            candidate = name + ++i;
        }
        return candidate;
    }

    /**
     * Resolves the name a method parameter is emitted under, looking in the innermost scope that
     * declares it and walking outwards so that a lambda body can capture a parameter of the
     * enclosing method.
     *
     * @param name The parameter name
     * @return The name to emit, or {@code null} if no scope declares the parameter
     */
    @Nullable
    String resolveParameter(String name) {
        for (RenderScope s = this; s != null; s = s.parent) {
            if (s.owner != null && s.owner.findParameter(name) != null) {
                return s.renames.getOrDefault(name, name);
            }
        }
        return null;
    }

    /**
     * Resolves a name recorded by {@link #rename(String, String)}, walking outwards.
     *
     * @param name The name in the model
     * @return The name to emit, or {@code null} if no scope renamed it
     */
    @Nullable
    String resolveRename(String name) {
        for (RenderScope s = this; s != null; s = s.parent) {
            String emittedName = s.renames.get(name);
            if (emittedName != null) {
                return emittedName;
            }
        }
        return null;
    }
}

