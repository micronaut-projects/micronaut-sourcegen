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
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.javapoet.AnnotationSpec;
import io.micronaut.sourcegen.javapoet.ClassName;
import io.micronaut.sourcegen.javapoet.TypeSpec;
import io.micronaut.sourcegen.model.ObjectDef;

/**
 * Reuse the Java source generator for Groovy.
 * Every generated class, record, enum and interface is annotated with
 * {@code groovy.transform.CompileStatic} so that the Groovy compiler compiles it statically.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class GroovyPoetSourceGenerator extends JavaPoetSourceGenerator {

    private static final ClassName COMPILE_STATIC = ClassName.get("groovy.transform", "CompileStatic");

    @Override
    public VisitorContext.Language getLanguage() {
        return VisitorContext.Language.GROOVY;
    }

    @Override
    protected void customizeTypeBuilder(ObjectDef objectDef, TypeSpec.Builder typeBuilder) {
        typeBuilder.addAnnotation(AnnotationSpec.builder(COMPILE_STATIC).build());
    }
}
