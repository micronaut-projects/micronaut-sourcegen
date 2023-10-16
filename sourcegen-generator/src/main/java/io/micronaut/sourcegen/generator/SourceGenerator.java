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
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.model.ObjectDef;

import java.io.IOException;
import java.io.Writer;

/**
 * Source code generator.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public interface SourceGenerator {

    /**
     * @return The source language of the generator
     */
    VisitorContext.Language getLanguage();

    /**
     * Write the source code.
     *
     * @param objectDef The object definition
     * @param writer           The writer
     * @throws IOException The IO exception
     */
    void write(ObjectDef objectDef, Writer writer) throws IOException;

}
