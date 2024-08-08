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
package io.micronaut.sourcegen.generator;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The source generators.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class SourceGenerators {

    private static List<SourceGenerator> sourceGenerators;

    private SourceGenerators() {
    }

    @NonNull
    public static synchronized List<SourceGenerator> getAll() {
        if (sourceGenerators == null) {
            sourceGenerators = SoftServiceLoader.load(SourceGenerator.class).collectAll();
        }
        return sourceGenerators;
    }

    @Nullable
    public static Optional<SourceGenerator> findByLanguage(VisitorContext.Language language) {
        return getAll().stream().filter(s -> s.getLanguage() == language).findAny();
    }

    /**
     * Utility method for handling exceptions.
     *
     * @param originatingElement The originating element.
     * @param annotation The annotation
     * @param exception The exception
     * @param postponeCallback A call back if compilation needs to be delayed to the next round
     */
    public static void handleFatalException(
        @NonNull Element originatingElement,
        @NonNull Class<? extends Annotation> annotation,
        @NonNull Exception exception,
        Consumer<RuntimeException> postponeCallback)  {
        if (exception.getClass().getSimpleName().equals("PostponeToNextRoundException") && exception instanceof RuntimeException runtimeException) {
            postponeCallback.accept(runtimeException);
        } else {
            String message = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
            throw new ProcessingException(originatingElement, "Failed to generate a @" + annotation.getSimpleName() + ": " + message, exception);
        }
    }

}
