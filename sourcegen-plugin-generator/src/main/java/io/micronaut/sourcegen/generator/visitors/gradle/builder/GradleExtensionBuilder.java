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
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassDef.ClassDefBuilder;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.InterfaceDef.InterfaceDefBuilder;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import io.micronaut.sourcegen.model.VariableDef.Local;
import io.micronaut.sourcegen.model.VariableDef.MethodParameter;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.micronaut.sourcegen.generator.visitors.gradle.builder.GradleTaskBuilder.TASK_SUFFIX;
import static io.micronaut.sourcegen.generator.visitors.gradle.builder.GradleTaskBuilder.createGradleProperty;

/**
 * A builder for {@link Type#GRADLE_EXTENSION}.
 * Creates a Gradle extension for calling a gradle task with the specification.
 */
@Internal
public class GradleExtensionBuilder implements GradleTypeBuilder {

    public static final String EXTENSION_NAME_SUFFIX = "Extension";
    public static final String DEFAULT_EXTENSION_NAME_PREFIX = "Default";
    public static final String TASK_CONFIGURATOR_SUFFIX = "TaskConfigurator";

    private static final TypeDef PROJECT_TYPE = TypeDef.of("org.gradle.api.Project");
    private static final TypeDef CONFIGURATION_TYPE = TypeDef.of("org.gradle.api.artifacts.Configuration");

    @Override
    public Type getType() {
        return Type.GRADLE_EXTENSION;
    }

    @Override
    @NonNull
    public List<ObjectDef> build(GradlePluginConfig pluginConfig) {
        return List.of(
            buildExtensionInterface(pluginConfig),
            buildDefaultExtension(pluginConfig)
        );
    }

    private ObjectDef buildExtensionInterface(GradlePluginConfig pluginConfig) {
        InterfaceDefBuilder builder = InterfaceDef.builder(pluginConfig.packageName() + "." + pluginConfig.namePrefix() + EXTENSION_NAME_SUFFIX)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("Configures the " + pluginConfig.namePrefix() + " execution.");

        for (GradleTaskConfig taskConfig: pluginConfig.tasks()) {
            ClassTypeDef specificationType = ClassTypeDef.of(pluginConfig.packageName()
                + "." + taskConfig.namePrefix() + GradleSpecificationBuilder.SPECIFICATION_NAME_SUFFIX);

            builder.addMethod(MethodDef.builder(taskConfig.extensionMethodName())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("name", String.class)
                .addParameter(ParameterDef.builder("action",
                    TypeDef.parameterized(ClassTypeDef.of("org.gradle.api.Action"), specificationType)
                ).build())
                .addJavadoc("Create a task for " + taskConfig.extensionMethodName() + "." +
                    "\n" + taskConfig.methodJavadoc() +
                    "\n@param name The unique identifier used to derive task names" +
                    "\n@param spec The configurable specification"
                )
                .build()
            );
        }
        return builder.build();
    }

    private ObjectDef buildDefaultExtension(GradlePluginConfig pluginConfig) {
        TypeDef interfaceType = TypeDef.of(pluginConfig.packageName() + "." + pluginConfig.namePrefix() + EXTENSION_NAME_SUFFIX);

        ClassDefBuilder builder = ClassDef.builder(pluginConfig.packageName() +
                "." + DEFAULT_EXTENSION_NAME_PREFIX + pluginConfig.namePrefix() + EXTENSION_NAME_SUFFIX)
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addSuperinterface(interfaceType)
            .addField(
                FieldDef.builder("names", TypeDef.parameterized(Set.class, String.class))
                    .addModifiers(Modifier.PROTECTED, Modifier.FINAL)
                    .initializer(ClassTypeDef.of(HashSet.class).instantiate())
                    .build()
            )
            .addField(FieldDef.builder("project", PROJECT_TYPE)
                .addModifiers(Modifier.PROTECTED, Modifier.FINAL).build())
            .addField(FieldDef.builder("classpath", CONFIGURATION_TYPE)
                .addModifiers(Modifier.PROTECTED, Modifier.FINAL).build());

        builder.addMethod(MethodDef.builder(MethodDef.CONSTRUCTOR)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation("javax.inject.Inject")
            .addParameter("project", PROJECT_TYPE)
            .addParameter("classpath", CONFIGURATION_TYPE)
            .build((t, params) ->
                StatementDef.multi(
                    t.field("project", PROJECT_TYPE).assign(params.get(0)),
                    t.field("classpath", CONFIGURATION_TYPE).assign(params.get(1))
                )
            ));

        for (GradleTaskConfig taskConfig: pluginConfig.tasks()) {
            ClassTypeDef specificationType = ClassTypeDef.of(pluginConfig.packageName()
                + "." + taskConfig.namePrefix() + GradleSpecificationBuilder.SPECIFICATION_NAME_SUFFIX);
            ClassTypeDef actionType = TypeDef.parameterized(ClassTypeDef.of("org.gradle.api.Action"), specificationType);

            builder.addMethod(MethodDef.builder(taskConfig.extensionMethodName())
                .overrides()
                .addModifiers(Modifier.PUBLIC)
                .addParameter("name", String.class)
                .addParameter(ParameterDef.builder("action", actionType).build())
                .build((t, params) -> buildExtensionMethod(t, params, pluginConfig, taskConfig, specificationType))
            );
            builder.addMethod(buildCreateTaskMethod(pluginConfig, taskConfig));
            builder.addMethod(MethodDef.builder("configureSpec")
                .addModifiers(Modifier.PROTECTED)
                .addParameter("spec", specificationType)
                .build((t, params) -> buildConfigureSpecMethod(taskConfig, t, params))
            );

            builder.addInnerType(buildTaskConfigurator(pluginConfig, taskConfig, specificationType));
        }

        return builder.build();
    }

    private ClassDef buildTaskConfigurator(
            GradlePluginConfig pluginConfig, GradleTaskConfig taskConfig, TypeDef specificationType
    ) {
        ClassTypeDef taskType = ClassTypeDef.of(pluginConfig.packageName() + "." + taskConfig.namePrefix() + TASK_SUFFIX);
        FieldDef specField = FieldDef.builder("spec", specificationType).build();
        FieldDef classpathField = FieldDef.builder("classpath", CONFIGURATION_TYPE).build();

        MethodDef execute = MethodDef.builder("execute")
            .addParameter(taskType)
            .overrides()
            .addModifiers(Modifier.PUBLIC)
            .build((t, params) -> {
                List<StatementDef> statements = new ArrayList<>();
                MethodParameter task = params.get(0);
                if (pluginConfig.taskGroup() != null) {
                    statements.add(task.invoke("setGroup", TypeDef.VOID, ExpressionDef.constant(pluginConfig.taskGroup())));
                }
                statements.add(task.invoke("getClasspath", ClassTypeDef.of("org.gradle.api.file.ConfigurableFileLocation"))
                    .invoke("from", TypeDef.VOID, t.field(classpathField))
                );
                statements.add(task.invoke("setDescription", TypeDef.VOID,
                    ExpressionDef.constant("Configure the " + taskConfig.extensionMethodName())));
                for (ParameterConfig parameter: taskConfig.parameters()) {
                    String getterName = "get" + NameUtils.capitalize(parameter.source().getName());
                    TypeDef getterType = createGradleProperty(parameter);
                    if (!parameter.internal()) {
                        StatementDef convention = task
                            .invoke(getterName, getterType)
                            .invoke("convention", getterType, t.field(specField).invoke(getterName, getterType));
                        statements.add(convention);
                    }
                }
                return StatementDef.multi(statements);
            });
        return ClassDef.builder(taskConfig.namePrefix() + TASK_CONFIGURATOR_SUFFIX)
            .addModifiers(Modifier.STATIC, Modifier.PROTECTED)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of("org.gradle.api.Action"), taskType))
            .addField(specField)
            .addField(classpathField)
            .addAllFieldsConstructor()
            .addMethod(execute)
            .build();
    }

    private MethodDef buildCreateTaskMethod(GradlePluginConfig pluginConfig, GradleTaskConfig taskConfig) {
        ClassTypeDef taskType = ClassTypeDef.of(pluginConfig.packageName() + "." + taskConfig.namePrefix() + TASK_SUFFIX);
        TypeDef taskProviderType = TypeDef.parameterized(ClassTypeDef.of("org.gradle.api.tasks.TaskProvider"), TypeDef.wildcardSubtypeOf(taskType));
        TypeDef taskContainerType = TypeDef.of("org.gradle.api.tasks.TaskContainer");
        TypeDef pluginConfiguratorType = TypeDef.parameterized(ClassTypeDef.of("org.gradle.api.Action"), taskType);

        return MethodDef.builder("create" + taskConfig.namePrefix() + "Task")
            .returns(taskProviderType)
            .addParameter("name", String.class)
            .addParameter("configurator", pluginConfiguratorType)
            .build((t, params) ->
                    t.field("project", PROJECT_TYPE)
                    .invoke("getTasks", taskContainerType)
                    .invoke("register", taskProviderType,
                        params.get(0),
                        taskType.getStaticField("class", TypeDef.CLASS),
                        params.get(1)
                    ).returning()
            );
    }

    private StatementDef buildConfigureSpecMethod(GradleTaskConfig taskConfig, VariableDef t, List<VariableDef.MethodParameter> params) {
        List<StatementDef> statements = new ArrayList<>();
        for (ParameterConfig parameter: taskConfig.parameters()) {
            String getterName = "get" + NameUtils.capitalize(parameter.source().getName());
            TypeDef getterType = createGradleProperty(parameter);
            if (parameter.defaultValue() != null && !parameter.internal()) {
                TypeDef type = parameter.type();
                StatementDef convention = params.get(0)
                    .invoke(getterName, getterType)
                    .invoke("convention", getterType, GradleTaskBuilder.createDefault(type, parameter.defaultValue()));
                statements.add(convention);
            }
        }
        return StatementDef.multi(statements);
    }

    private StatementDef buildExtensionMethod(
            VariableDef t, List<VariableDef.MethodParameter> params, GradlePluginConfig pluginConfig, GradleTaskConfig taskConfig, ClassTypeDef specificationType
    ) {
        StatementDef ifStatement = new StatementDef.If(
            t.field("names", TypeDef.of(String.class)).invoke("add", TypeDef.of(boolean.class), params.get(0)).isFalse(),
            new StatementDef.Throw(ClassTypeDef.of("org.gradle.api.GradleException")
                .instantiate(TypeDef.STRING.invokeStatic("format", TypeDef.STRING,
                    ExpressionDef.constant("An " + taskConfig.extensionMethodName() + " definition with name '%s' was already created"),
                    params.get(0))
                )
            )
        );
        TypeDef objectFactoryType = TypeDef.of("org.gradle.api.type.ObjectFactory");
        Local spec = new Local("spec", specificationType);
        StatementDef specCreation = new StatementDef.DefineAndAssign(
            spec,
            t.field("project", PROJECT_TYPE)
                .invoke("getObjects", objectFactoryType)
                .invoke("newInstance", specificationType, specificationType.getStaticField("class", TypeDef.CLASS))
        );
        StatementDef configureSpec = t.invoke("configureSpec", TypeDef.VOID, spec);
        StatementDef actionCall = params.get(1).invoke("execute", TypeDef.VOID, spec);

        ClassTypeDef taskType = ClassTypeDef.of(pluginConfig.packageName() + "." + taskConfig.namePrefix() + TASK_SUFFIX);
        TypeDef taskProviderType = TypeDef.parameterized(ClassTypeDef.of("org.gradle.api.tasks.TaskProvider"), TypeDef.wildcardSubtypeOf(taskType));
        ExpressionDef pluginConfigurator = ClassTypeDef.of(taskConfig.namePrefix() + TASK_CONFIGURATOR_SUFFIX)
           .instantiate(spec, t.field("classpath", CONFIGURATION_TYPE));
        Local task = new Local("task", taskProviderType);
        StatementDef taskCreation = new StatementDef.DefineAndAssign(
            task,
            t.invoke("create" + taskConfig.namePrefix() + "Task", taskProviderType, params.get(0), pluginConfigurator)
        );
        // TODO source sets
        return StatementDef.multi(
            ifStatement,
            specCreation,
            configureSpec,
            actionCall,
            taskCreation
        );
    }

}
