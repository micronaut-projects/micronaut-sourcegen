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
import io.micronaut.sourcegen.annotations.GenerateGradlePlugin.Type;
import io.micronaut.sourcegen.generator.visitors.gradle.GradlePluginUtils.GradlePluginConfig;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassDef.ClassDefBuilder;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.StatementDef.DefineAndAssign;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef.Local;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;

import static io.micronaut.sourcegen.generator.visitors.gradle.builder.GradleExtensionBuilder.DEFAULT_EXTENSION_NAME_PREFIX;
import static io.micronaut.sourcegen.generator.visitors.gradle.builder.GradleExtensionBuilder.EXTENSION_NAME_SUFFIX;

/**
 * A builder for {@link Type#GRADLE_PLUGIN}.
 * Creates a plugin that configures an extension and task.
 */
@Internal
public class GradlePluginBuilder implements GradleTypeBuilder {

    public static final String PLUGIN_SUFFIX = "Plugin";

    private static final String MICRONAUT_BASE_PLUGIN = "io.micronaut.gradle.MicronautBasePlugin";
    private static final String MICRONAUT_PLUGINS_HELPER = "io.micronaut.gradle.PluginsHelper";
    private static final ClassTypeDef PROJECT_TYPE = ClassTypeDef.of("org.gradle.api.Project");
    private static final ClassTypeDef CONFIGURATION_TYPE = ClassTypeDef.of("org.gradle.api.artifacts.Configuration");

    @Override
    public Type getType() {
        return Type.GRADLE_PLUGIN;
    }

    @Override
    @NonNull
    public List<ObjectDef> build(GradlePluginConfig pluginConfig) {
        String pluginType = pluginConfig.packageName() + "." + pluginConfig.namePrefix() + PLUGIN_SUFFIX;
        ClassDefBuilder builder = ClassDef.builder(pluginType)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(
                ClassTypeDef.of("org.gradle.api.Plugin"),
                PROJECT_TYPE
            ));
        builder.addMethod(createExtensionMethod(pluginConfig));
        builder.addMethod(createApplyMethod(pluginConfig));
        return List.of(builder.build());
    }

    private MethodDef createApplyMethod(GradlePluginConfig pluginConfig) {
        ClassTypeDef extensionType = ClassTypeDef.of(pluginConfig.packageName() + "."
            + pluginConfig.namePrefix() + EXTENSION_NAME_SUFFIX);

        return MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("project", ClassTypeDef.of("org.gradle.api.Project"))
            .build((t, params) -> {
                List<StatementDef> statements = new ArrayList<>();
                if (pluginConfig.micronautPlugin()) {
                    params.get(0)
                        .invoke("getPluginManager", ClassTypeDef.of("org.gradle.api.plugins.PluginManager"))
                        .invoke("apply", TypeDef.VOID, ClassTypeDef.of(MICRONAUT_BASE_PLUGIN).getStaticField("class", TypeDef.CLASS));
                }
                ExpressionDef configurations = params.get(0).invoke("getConfigurations", TypeDef.of("org.gradle.api.artifacts.ConfigurationContainer"));
                ExpressionDef dependencyHandler = params.get(0).invoke("getDependencies", TypeDef.of("org.gradle.api.artifacts.dsl.DependencyHandler"));
                TypeDef dependencyType = TypeDef.of("org.gradle.api.artifacts.Dependency");

                Local dependencies = new Local("dependencies", CONFIGURATION_TYPE);
                statements.add(new DefineAndAssign(
                    dependencies,
                    configurations.invoke("create", CONFIGURATION_TYPE, ExpressionDef.constant(pluginConfig.namePrefix() + "Configuration"))
                ));
                statements.add(dependencies.invoke("setCanBeResolved", TypeDef.VOID, ExpressionDef.constant(false)));
                statements.add(dependencies.invoke("setCanBeConsumed", TypeDef.VOID, ExpressionDef.constant(false)));
                statements.add(dependencies.invoke("setDescription", TypeDef.VOID, ExpressionDef.constant("The " + pluginConfig.namePrefix() + " worker dependencies")));
                if (pluginConfig.dependency() != null) {
                    statements.add(dependencies.invoke("getDependencies", TypeDef.of("org.gradle.api.artifacts.DependencySet"))
                        .invoke("add", TypeDef.VOID, dependencyHandler.invoke("create", dependencyType, ExpressionDef.constant(pluginConfig.dependency()))));
                }

                Local classpath = new Local("classpath", CONFIGURATION_TYPE);
                statements.add(new DefineAndAssign(
                    classpath,
                    configurations.invoke("create", CONFIGURATION_TYPE, ExpressionDef.constant(pluginConfig.namePrefix() + "Classpath"))
                ));
                statements.add(classpath.invoke("setCanBeResolved", TypeDef.VOID, ExpressionDef.constant(true)));
                statements.add(classpath.invoke("setCanBeConsumed", TypeDef.VOID, ExpressionDef.constant(false)));
                statements.add(classpath.invoke("setDescription", TypeDef.VOID, ExpressionDef.constant("The " + pluginConfig.namePrefix() + " worker classpath")));
                statements.add(classpath.invoke("extendsFrom", TypeDef.VOID, dependencies));

                statements.add(t.invoke("createExtension", extensionType, params.get(0), classpath));
                return StatementDef.multi(statements);
            });
    }

    private MethodDef createExtensionMethod(GradlePluginConfig pluginConfig) {
        ClassTypeDef extensionType = ClassTypeDef.of(pluginConfig.packageName() + "." + pluginConfig.namePrefix() + EXTENSION_NAME_SUFFIX);
        ClassTypeDef defaultExtensionType = ClassTypeDef.of(pluginConfig.packageName() + "." + DEFAULT_EXTENSION_NAME_PREFIX + pluginConfig.namePrefix() + EXTENSION_NAME_SUFFIX);

        return MethodDef.builder("createExtension")
            .addModifiers(Modifier.PROTECTED)
            .returns(extensionType)
            .addParameter("project", PROJECT_TYPE)
            .addParameter("classpath", CONFIGURATION_TYPE)
            .build((t, params) -> {
                ExpressionDef root = params.get(0);
                if (pluginConfig.micronautPlugin()) {
                    root = ClassTypeDef.of(MICRONAUT_PLUGINS_HELPER)
                        .invokeStatic("findMicronautExtension", TypeDef.of("io.micronaut.gradle.MicronautExtension"), params.get(0));
                }
                ExpressionDef extensions = root.invoke("getExtensions", ClassTypeDef.of("org.gradle.api.plugins.ExtensionContainer"));
                return new StatementDef.Return(extensions.invoke("create", extensionType,
                    extensionType.getStaticField("class", TypeDef.CLASS),
                    ExpressionDef.constant(pluginConfig.namePrefix()),
                    defaultExtensionType.getStaticField("class", TypeDef.CLASS),
                    params.get(0),
                    params.get(1)
                ));
            });
    }

}
