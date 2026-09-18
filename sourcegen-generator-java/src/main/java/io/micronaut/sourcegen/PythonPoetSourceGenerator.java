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
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.model.ObjectDef;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;

/**
 * Reuse the Java source generator for the classes compiled by the Python compiler (micronaut-inject-python).
 * The Java sources are compiled together with the stubs of the Python classes, and Python code uses the
 * generated types through host interop.
 *
 * @author Graeme Rocher
 * @since 2.2.1
 */
@Internal
public final class PythonPoetSourceGenerator implements SourceGenerator {

    /**
     * {@code VisitorContext.Language.PYTHON} exists since Micronaut core 5.2. It is looked up by name so that this
     * module keeps working with core 5.1, where no visitor context reports it and this generator is never selected.
     */
    private static final VisitorContext.@Nullable Language PYTHON = Arrays.stream(VisitorContext.Language.values())
        .filter(language -> language.name().equals("PYTHON"))
        .findFirst()
        .orElse(null);

    private final JavaPoetSourceGenerator javaSourceGenerator = new JavaPoetSourceGenerator();

    @Override
    public VisitorContext.@Nullable Language getLanguage() {
        return PYTHON;
    }

    @Override
    public void write(ObjectDef objectDef, Writer writer) throws IOException {
        javaSourceGenerator.write(objectDef, writer);
    }
}
