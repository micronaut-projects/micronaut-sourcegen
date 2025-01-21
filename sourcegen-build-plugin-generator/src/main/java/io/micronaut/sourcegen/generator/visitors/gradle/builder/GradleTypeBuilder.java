/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.sourcegen.generator.visitors.gradle.builder;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.sourcegen.annotations.GenerateGradlePlugin;
import io.micronaut.sourcegen.generator.visitors.gradle.GradlePluginUtils;
import io.micronaut.sourcegen.model.ObjectDef;

import java.util.List;

/**
 * An interface for a Gradle plugin builder type.
 */
@Internal
public interface GradleTypeBuilder {

    /**
     * Get the gradle type it can generate.
     *
     * @return The type
     */
    @NonNull GenerateGradlePlugin.Type getType();

    /**
     * Generate the gradle type.
     *
     * @param pluginConfig The configuration
     * @return The generated objects for the type
     */
    @NonNull List<ObjectDef> build(@NonNull GradlePluginUtils.GradlePluginConfig pluginConfig);

}
