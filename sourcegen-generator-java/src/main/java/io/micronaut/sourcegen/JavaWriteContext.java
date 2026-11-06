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
package io.micronaut.sourcegen;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.GenerationScope;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Function;

/**
 * The context of writing one file: the visitor context the file is written with, the methods being written, and the
 * rules that depend on either. A writer creates one for each file, which its renderers share.
 *
 * @since 2.3
 */
@Internal
final class JavaWriteContext {

    private final GenerationScope scope;
    // The methods being written, the innermost first: a lambda body is one written in another
    private final Deque<MethodDef> enclosingMethods = new ArrayDeque<>();
    private final JavaPoetNames names;
    private final JavaConversionRules conversions;
    private final JavaExceptionRules exceptions;
    private final JavaSourceRules sourceRules;
    private final JavaGenerics generics;

    /**
     * @param visitorContext The context of the file, used to tell a nested class from a generated top-level one by
     *                       name and to look up the supertypes of an override that are only known by name; {@code null}
     *                       where the file is written without one
     * @param topLevel       The top level definition of the file
     */
    JavaWriteContext(@Nullable VisitorContext visitorContext, ObjectDef topLevel) {
        this.scope = GenerationScope.of(topLevel, visitorContext);
        this.names = new JavaPoetNames(scope);
        this.conversions = new JavaConversionRules(this);
        this.exceptions = new JavaExceptionRules(this, topLevel);
        this.sourceRules = new JavaSourceRules(this);
        this.generics = new JavaGenerics(conversions, scope);
    }

    @Nullable
    VisitorContext visitorContext() {
        return scope.visitorContext();
    }

    /**
     * @return The lookup of a class the compiler knows by its name, or {@code null} without a visitor context
     */
    @Nullable
    Function<String, @Nullable ClassElement> elementLookup() {
        return scope.elementLookup();
    }

    /**
     * @return The scope of the file: its top level definition and the visitor context it is written with
     */
    GenerationScope scope() {
        return scope;
    }

    /**
     * @param method The method whose body is about to be written
     */
    void enterMethod(MethodDef method) {
        enclosingMethods.addFirst(method);
    }

    /**
     * Ends the method {@link #enterMethod(MethodDef)} started.
     */
    void exitMethod() {
        enclosingMethods.removeFirst();
    }

    /**
     * @return The methods being written, the innermost first
     */
    Iterable<MethodDef> enclosingMethods() {
        return enclosingMethods;
    }

    JavaPoetNames names() {
        return names;
    }

    JavaConversionRules conversions() {
        return conversions;
    }

    JavaExceptionRules exceptions() {
        return exceptions;
    }

    JavaSourceRules sourceRules() {
        return sourceRules;
    }

    JavaGenerics generics() {
        return generics;
    }
}
