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
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.annotations.GenerateGradlePlugin;
import io.micronaut.sourcegen.annotations.GenerateGradlePlugin.Type;
import io.micronaut.sourcegen.generator.visitors.ModelUtils;
import io.micronaut.sourcegen.generator.visitors.PluginUtils;
import io.micronaut.sourcegen.generator.visitors.PluginUtils.ParameterConfig;
import io.micronaut.sourcegen.generator.visitors.gradle.GradlePluginUtils.GradlePluginConfig;
import io.micronaut.sourcegen.generator.visitors.gradle.GradlePluginUtils.GradleTaskConfig;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassDef.ClassDefBuilder;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ClassTypeDef.ClassDefType;
import io.micronaut.sourcegen.model.ClassTypeDef.ClassElementType;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.ExpressionDef.Constant;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.InterfaceDef.InterfaceDefBuilder;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodDef.MethodDefBuilder;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A builder for {@link GenerateGradlePlugin.Type#GRADLE_TASK}.
 * Creates a task, work action and work action parameters given a plugin task configuration.
 */
@Internal
public class GradleTaskBuilder implements GradleTypeBuilder {

    public static final String TASK_SUFFIX = "Task";

    @Override
    public Type getType() {
        return Type.GRADLE_TASK;
    }

    @Override
    @NonNull
    public List<ObjectDef> build(GradlePluginConfig pluginConfig) {
        List<ObjectDef> objects = new ArrayList<>();
        for (GradleTaskConfig taskConfig: pluginConfig.tasks()) {
            objects.addAll(buildTask(pluginConfig.packageName(), taskConfig));
        }
        return objects;
    }

    private List<ObjectDef> buildTask(String packageName, GradleTaskConfig taskConfig) {
        String taskType = packageName + "." + taskConfig.namePrefix() + TASK_SUFFIX;
        ClassDefBuilder builder = ClassDef.builder(taskType)
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .superclass(ClassTypeDef.of("org.gradle.api.DefaultTask"))
            .addJavadoc(taskConfig.taskJavadoc());
        if (taskConfig.cacheable()) {
            builder.addAnnotation("org.gradle.api.tasks.CacheableTask");
        }
        builder.addInnerType(createWorkAction(taskConfig));
        builder.addInnerType(createWorkActionParameters(taskConfig));
        builder.addInnerType(createWorkActionParameterConfigurator(TypeDef.of(taskType), taskConfig));
        builder.addInnerType(createClasspathConfigurator(TypeDef.of(taskType), taskConfig));

        for (ParameterConfig parameter: taskConfig.parameters()) {
            MethodDefBuilder propBuilder = MethodDef
                .builder("get" + NameUtils.capitalize(parameter.source().getName()))
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addJavadoc(parameter.javadoc())
                .returns(createGradleProperty(parameter));
            if (parameter.output()) {
                if (parameter.source().getType().isAssignable(File.class)) {
                    if (parameter.directory()) {
                        propBuilder.addAnnotation(AnnotationDef.builder(ClassTypeDef.of("org.gradle.api.tasks.OutputDirectory")).build());
                    } else {
                        propBuilder.addAnnotation(AnnotationDef.builder(ClassTypeDef.of("org.gradle.api.tasks.OutputFile")).build());
                    }
                }
            } else {
                propBuilder.addAnnotation("org.gradle.api.tasks.Input");
                if (parameter.source().getType().isAssignable(File.class)) {
                    if (parameter.directory()) {
                        propBuilder.addAnnotation(AnnotationDef.builder(ClassTypeDef.of("org.gradle.api.tasks.InputDirectory")).build());
                    } else {
                        propBuilder.addAnnotation(AnnotationDef.builder(ClassTypeDef.of("org.gradle.api.tasks.InputFile")).build());
                    }
                    ClassTypeDef pathSensitivityType = ClassTypeDef.of("org.gradle.api.tasks.PathSensitivity");
                    ClassTypeDef pathSensitiveType = ClassTypeDef.of("org.gradle.api.tasks.PathSensitive");
                    propBuilder.addAnnotation(AnnotationDef.builder(pathSensitiveType)
                        .addMember("value", pathSensitivityType.getStaticField(taskConfig.pathSensitivity(), pathSensitivityType))
                        .build()
                    );
                }
            }

            if (!parameter.required()) {
                propBuilder.addAnnotation("org.gradle.api.tasks.Optional");
            }
            builder.addMethod(propBuilder.build());
        }

        TypeDef classpathType = TypeDef.of("org.gradle.api.file.ConfigurableFileCollection");
        builder.addMethod(MethodDef.builder("getClasspath")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(classpathType)
            .addAnnotation("org.gradle.api.tasks.Classpath")
            .build()
        );

        TypeDef workerExecutorType = TypeDef.of("org.gradle.workers.WorkerExecutor");
        builder.addMethod(MethodDef.builder("getWorkerExecutor")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .returns(workerExecutorType)
            .addAnnotation("javax.inject.Inject")
            .build()
        );

        builder.addMethod(MethodDef.builder("execute")
            .returns(TypeDef.VOID)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation("org.gradle.api.tasks.TaskAction")
            .addJavadoc(taskConfig.methodJavadoc())
            .build((t, params) ->
                t.invoke("getWorkerExecutor", workerExecutorType)
                    .invoke("classLoaderIsolation",
                        workerExecutorType,
                        ClassTypeDef.of(taskConfig.namePrefix() + "ClasspathConfigurator").instantiate(t)
                    )
                    .invoke("submit", TypeDef.VOID,
                        ClassTypeDef.of(taskConfig.namePrefix() + "WorkAction").getStaticField("class", TypeDef.CLASS),
                        ClassTypeDef.of(taskConfig.namePrefix() + "WorkActionParameterConfigurator").instantiate(t)
                    )
            )
        );

        return List.of(builder.build());
    }

    private ClassDef createWorkActionParameterConfigurator(TypeDef taskType, GradleTaskConfig taskConfig) {
        TypeDef parametersType = TypeDef.of(taskConfig.namePrefix() + "WorkActionParameters");
        FieldDef taskField = FieldDef.builder("task").ofType(taskType).build();
        return ClassDef.builder(taskConfig.namePrefix() + "WorkActionParameterConfigurator")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addSuperinterface(TypeDef.parameterized(
                ClassTypeDef.of("org.gradle.api.Action"),
                parametersType
            ))
            .addField(taskField)
            .addAllFieldsConstructor(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("execute")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.VOID)
                .overrides()
                .addParameter(ParameterDef.of("params", parametersType))
                .build((t, params) -> {
                    List<StatementDef> statements = new ArrayList<>();
                    for (ParameterConfig parameter: taskConfig.parameters()) {
                        String getterName = "get" + NameUtils.capitalize(parameter.source().getName());
                        TypeDef getterType = createGradleProperty(parameter);
                        ExpressionDef def = t.field(taskField).invoke(getterName, getterType);
                        if (!parameter.required()) {
                            if (parameter.defaultValue() != null) {
                                TypeDef type = parameter.type();
                                def = def.invoke(
                                    "orElse",
                                    type,
                                    createDefault(type, parameter.defaultValue())
                                );
                            } else {
                                def = def.invoke("getOrNull", parameter.type());
                            }
                        }
                        statements.add(params.get(0)
                            .invoke(getterName, getterType)
                            .invoke("set", TypeDef.VOID, def)
                        );
                    }
                    return StatementDef.multi(statements);
                })
            )
            .build();
    }

    static ExpressionDef createDefault(TypeDef type, String value) {
        if (type instanceof ClassElementType classElementType) {
            return ExpressionDef.constant(classElementType.classElement(), type, value);
        } else if (type instanceof TypeDef.Primitive primitiveType) {
            return ClassUtils.getPrimitiveType(primitiveType.name()).flatMap(t ->
                ConversionService.SHARED.convert(value, t)
            ).map(o -> new Constant(type, o)).orElse(null);
        } else if (type instanceof ClassDefType classDefType) {
            if (classDefType.objectDef() instanceof EnumDef enumDef) {
                return classDefType.getStaticField(value, type);
            }
        }
        throw new UnsupportedOperationException("Cannot create default value of type " + type);
    }

    private ClassDef createClasspathConfigurator(TypeDef taskType, GradleTaskConfig taskConfig) {
        FieldDef taskField = FieldDef.builder("task").ofType(taskType).build();
        TypeDef specType = TypeDef.of("org.gradle.workers.ClassLoaderWorkerSpec");
        TypeDef classpathType = TypeDef.of("org.gradle.api.file.ConfigurableFileCollection");
        return ClassDef.builder(taskConfig.namePrefix() + "ClasspathConfigurator")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addSuperinterface(TypeDef.parameterized(
                ClassTypeDef.of("org.gradle.api.Action"),
                specType
            ))
            .addField(taskField)
            .addAllFieldsConstructor(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("execute")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.VOID)
                .overrides()
                .addParameter(ParameterDef.of("spec", specType))
                .build((t, params) ->
                    params.get(0)
                        .invoke("getClasspath", classpathType)
                        .invoke("from", TypeDef.VOID, t.field(taskField).invoke("getClasspath", classpathType))
                )
            )
            .build();
    }

    private InterfaceDef createWorkActionParameters(GradleTaskConfig taskConfig) {
        InterfaceDefBuilder builder = InterfaceDef.builder(taskConfig.namePrefix() + "WorkActionParameters")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ClassTypeDef.of("org.gradle.workers.WorkParameters"));
        for (ParameterConfig parameter: taskConfig.parameters()) {
            MethodDefBuilder propBuilder = MethodDef
                .builder("get" + NameUtils.capitalize(parameter.source().getName()))
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(createGradleProperty(parameter));
            builder.addMethod(propBuilder.build());
        }
        return builder.build();
    }

    private ClassDef createWorkAction(GradleTaskConfig taskConfig) {
        ClassTypeDef parametersType = ClassTypeDef.of(taskConfig.namePrefix() + "WorkActionParameters");
        MethodDef executeMethod = MethodDef
            .builder("execute")
            .returns(TypeDef.VOID)
            .addModifiers(Modifier.PUBLIC)
            .overrides()
            .build((t, params) -> runTask(taskConfig, t, parametersType));
        return ClassDef.builder(taskConfig.namePrefix() + "WorkAction")
            .addSuperinterface(TypeDef.parameterized(
                ClassTypeDef.of("org.gradle.workers.WorkAction"),
                parametersType
            ))
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT, Modifier.STATIC)
            .addMethod(executeMethod)
            .build();
    }

    private StatementDef runTask(GradleTaskConfig taskConfig, VariableDef.This t, ClassTypeDef parametersType) {
        List<StatementDef> statements = new ArrayList<>();
        List<ExpressionDef> params = new ArrayList<>();
        statements.add(t.invoke("getParameters", parametersType).newLocal("parameters"));

        for (ParameterConfig parameter: taskConfig.parameters()) {
            ExpressionDef expression = new VariableDef.Local("parameters", parametersType)
                .invoke("get" + NameUtils.capitalize(parameter.source().getName()), createGradleProperty(parameter))
                .invoke("get", parameter.type());
            if (parameter.source().getType().isAssignable(File.class)) {
                expression = expression.invoke("getAsFile", TypeDef.of(File.class));
            }
            params.add(ModelUtils.convertParameterIfRequired(parameter, statements, expression));
        }
        statements.add(PluginUtils.executeTaskMethod(taskConfig.source(), taskConfig.methodName(), taskConfig.parameters(), params));
        return StatementDef.multi(statements);
    }

    static TypeDef createGradleProperty(ParameterConfig parameter) {
        ClassElement type = parameter.source().getType();
        if (type.isAssignable(File.class)) {
            if (parameter.directory()) {
                return ClassTypeDef.of("org.gradle.api.file.DirectoryProperty");
            }
            return ClassTypeDef.of("org.gradle.api.file.RegularFileProperty");
        }
        if (type.isAssignable(Map.class)) {
            Map<String, ClassElement> typeArgs = type.getGenericType().getTypeArguments();
            return TypeDef.parameterized(
                ClassTypeDef.of("org.gradle.api.provider.MapProperty"),
                ClassTypeDef.of(typeArgs.get("K")),
                ClassTypeDef.of(typeArgs.get("V"))
            );
        } else if (type.isAssignable(List.class)) {
            Map<String, ClassElement> typeArgs = type.getGenericType().getTypeArguments();
            return TypeDef.parameterized(
                ClassTypeDef.of("org.gradle.api.provider.ListProperty"),
                ClassTypeDef.of(typeArgs.get("E"))
            );
        } else if (type.isAssignable(Set.class)) {
            Map<String, ClassElement> typeArgs = type.getGenericType().getTypeArguments();
            return TypeDef.parameterized(
                ClassTypeDef.of("org.gradle.api.provider.SetProperty"),
                ClassTypeDef.of(typeArgs.get("E"))
            );
        } else {
            return TypeDef.parameterized(
                ClassTypeDef.of("org.gradle.api.provider.Property"),
                parameter.type()
            );
        }
    }

}
