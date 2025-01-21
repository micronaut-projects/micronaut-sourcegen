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
import io.micronaut.core.naming.NameUtils;
import io.micronaut.sourcegen.annotations.GenerateGradlePlugin.Type;
import io.micronaut.sourcegen.generator.visitors.PluginUtils.ParameterConfig;
import io.micronaut.sourcegen.generator.visitors.gradle.GradlePluginUtils.GradlePluginConfig;
import io.micronaut.sourcegen.generator.visitors.gradle.GradlePluginUtils.GradleTaskConfig;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.InterfaceDef.InterfaceDefBuilder;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodDef.MethodDefBuilder;
import io.micronaut.sourcegen.model.ObjectDef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static io.micronaut.sourcegen.generator.visitors.gradle.builder.GradleTaskBuilder.createGradleProperty;

/**
 * A builder for {@link Type#GRADLE_SPECIFICATION}.
 * Creates a Gradle specification for configuring a gradle task.
 */
@Internal
public class GradleSpecificationBuilder implements GradleTypeBuilder {

    public static final String SPECIFICATION_NAME_SUFFIX = "Spec";

    @Override
    public Type getType() {
        return Type.GRADLE_SPECIFICATION;
    }

    @Override
    @NonNull
    public List<ObjectDef> build(GradlePluginConfig pluginConfig) {
        List<ObjectDef> objects = new ArrayList<>();
        for (GradleTaskConfig taskConfig: pluginConfig.tasks()) {
            objects.add(buildForTask(pluginConfig.packageName(), taskConfig));
        }
        return objects;
    }

    private ObjectDef buildForTask(String packageName, GradleTaskConfig taskConfig) {
        InterfaceDefBuilder builder = InterfaceDef.builder(packageName + "." + taskConfig.namePrefix() + SPECIFICATION_NAME_SUFFIX)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Specification that is used for configuring " + taskConfig.namePrefix() + " task.\n" +
                taskConfig.taskJavadoc());
        for (ParameterConfig parameter: taskConfig.parameters()) {
            if (parameter.internal()) {
                continue;
            }
            MethodDefBuilder propBuilder = MethodDef
                .builder("get" + NameUtils.capitalize(parameter.source().getName()))
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addJavadoc(parameter.javadoc())
                .returns(createGradleProperty(parameter));
            builder.addMethod(propBuilder.build());
        }
        return builder.build();
    }

}
