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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.inject.ast.ClassElement;

import java.util.Optional;

/**
 * Looks up a type of the compilation the writer is running in.
 *
 * <p>A generated class often references types that are still being compiled and so have no class
 * file to read. An annotation processor can supply its own view of the compilation, such as
 * {@code visitorContext::getClassElement}, so that the writer can resolve their hierarchy while it
 * computes stack maps.</p>
 *
 * @since 2.2
 */
@Experimental
@FunctionalInterface
public interface CompilationTypes {

    /**
     * @param name The binary name of the type, such as {@code com.example.Outer$Inner}
     * @return The type, or empty when this compilation does not know it
     */
    Optional<ClassElement> find(String name);
}
