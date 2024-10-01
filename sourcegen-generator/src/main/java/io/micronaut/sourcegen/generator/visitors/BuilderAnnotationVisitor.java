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
package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Creator;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.Builder;
import io.micronaut.sourcegen.annotations.Singular;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassDef.ClassDefBuilder;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static io.micronaut.sourcegen.generator.visitors.Singulars.singularize;

/**
 * The visitor that is generation a builder.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Internal
public final class BuilderAnnotationVisitor implements TypeElementVisitor<Builder, Object> {

    public static final String BUILDER_ANNOTATED_WITH_MEMBER = "annotatedWith";

    private final Set<String> processed = new HashSet<>();

    @Override
    public @NonNull VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void start(VisitorContext visitorContext) {
        processed.clear();
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of(Builder.class.getName());
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (processed.contains(element.getName())) {
            return;
        }
        try {
            String simpleName = element.getSimpleName() + "Builder";
            String builderClassName = element.getPackageName() + "." + simpleName;

            ClassTypeDef builderType = ClassTypeDef.of(builderClassName);

            ClassDef.ClassDefBuilder builder = ClassDef.builder(builderClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
            addAnnotations(builder, element.getAnnotation(Builder.class));

            List<PropertyElement> properties = element.getBeanProperties();
            for (PropertyElement beanProperty : properties) {
                createModifyPropertyMethod(builder, beanProperty, ExpressionDef::returning);
            }

            builder.addMethod(MethodDef.constructor().build());
            if (!properties.isEmpty()) {
                builder.addMethod(createAllPropertiesConstructor(builderType, properties));
            }

            builder.addMethod(createBuilderMethod(builderType));
            builder.addMethod(createBuildMethod(element));

            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
            if (sourceGenerator == null) {
                return;
            }

            ClassDef builderDef = builder.build();
            processed.add(element.getName());
            context.visitGeneratedSourceFile(
                builderDef.getPackageName(),
                builderDef.getSimpleName(),
                element
            ).ifPresent(sourceFile -> {
                try {
                    sourceFile.write(
                        writer -> sourceGenerator.write(builderDef, writer)
                    );
                } catch (Exception e) {
                    throw new ProcessingException(element, "Failed to generate a builder: " + e.getMessage(), e);
                }
            });
        } catch (ProcessingException e) {
            throw e;
        } catch (Exception e) {
            SourceGenerators.handleFatalException(
                element,
                Builder.class,
                e,
                (exception -> {
                    processed.remove(element.getName());
                    throw exception;
                })
            );
        }
    }

    static void addAnnotations(ClassDefBuilder builder, AnnotationValue<?> annotation) {
        Optional<AnnotationClassValue[]> annotatedWith = annotation.getConvertibleValues()
            .get(BUILDER_ANNOTATED_WITH_MEMBER, AnnotationClassValue[].class);
        if (annotatedWith.isEmpty()) {
            // Apply the default annotation
            builder.addAnnotation(Introspected.class);
        } else {
            for (AnnotationClassValue<?> value: annotatedWith.get()) {
                builder.addAnnotation(value.getName());
            }
        }
    }

    static MethodDef createAllPropertiesConstructor(ClassTypeDef builderType, List<PropertyElement> properties) {
        MethodDef.MethodDefBuilder builder = MethodDef.constructor()
            .addAnnotation(Creator.class);
        VariableDef.This self = new VariableDef.This(builderType);
        for (PropertyElement parameter : properties) {
            ParameterDef parameterDef = ParameterDef.of(parameter.getName(), TypeDef.of(parameter.getType()));
            builder.addParameter(parameterDef);
            if (parameter.hasAnnotation(Singular.class)) {
                if (parameter.getType().getName().equals(Iterable.class.getName())) {
                    builder.addStatement(
                        iterableToArrayListStatement(self, parameterDef)
                    );
                } else if (parameter.getType().isAssignable(Map.class)) {
                    builder.addStatement(
                        mapToArrayListStatement(self, parameterDef)
                    );
                } else {
                    builder.addStatement(
                        self.field(parameterDef.getName(), parameterDef.getType())
                            .assign(ClassTypeDef.of(ArrayList.class).instantiate(parameterDef.asExpression()))
                    );
                }
            } else {
                builder.addStatement(
                    self.field(parameterDef.getName(), parameterDef.getType()).assign(parameterDef)
                );
            }
        }
        return builder.build();
    }

    private static StatementDef iterableToArrayListStatement(VariableDef.This self, ParameterDef parameterDef) {
        return ClassTypeDef.of(ArrayList.class)
            .instantiate()
            .newLocal(parameterDef.getName() + "ArrayList", arrayListVar ->
                parameterDef.asExpression()
                    .invoke("iterator", ClassTypeDef.of(Iterator.class))
                    .newLocal(parameterDef.getName() + "Iterator", iteratorVar ->
                        parameterDef.asExpression().isNonNull().asConditionIf(
                                iteratorVar.invoke("hasNext", TypeDef.primitive(boolean.class))
                                    .whileLoop(
                                        arrayListVar.invoke("add", TypeDef.of(boolean.class), iteratorVar.invoke("next", ClassTypeDef.OBJECT))
                                    )
                            )
                            .after(
                                self.field(parameterDef.getName(), parameterDef.getType()).assign(arrayListVar)
                            )));
    }

    private static StatementDef mapToArrayListStatement(VariableDef.This self, ParameterDef parameterDef) {
        return self.field(parameterDef.getName(), parameterDef.getType())
            .assign(
                ClassTypeDef.of(ArrayList.class)
                    .instantiate(
                        parameterDef.asExpression().invoke("entrySet", ClassTypeDef.of(Map.Entry.class))
                    )
            );
    }

    private MethodDef createBuilderMethod(ClassTypeDef builderType) {
        return MethodDef.builder("builder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(builderType)
            .addStatement(builderType.instantiate().returning())
            .build();
    }

    static void createModifyPropertyMethod(ClassDef.ClassDefBuilder classDefBuilder,
                                           PropertyElement beanProperty,
                                           Function<VariableDef.This, StatementDef> returningExpressionProvider) {
        if (beanProperty.hasAnnotation(Singular.class)) {
            createSingularPropertyMethods(classDefBuilder, beanProperty, returningExpressionProvider);
        } else {
            createDefaultModifyPropertyMethod(classDefBuilder, beanProperty, returningExpressionProvider);
        }
    }

    private static void createDefaultModifyPropertyMethod(ClassDef.ClassDefBuilder classDefBuilder,
                                                          PropertyElement beanProperty,
                                                          Function<VariableDef.This, StatementDef> returningExpressionProvider) {
        TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
        FieldDef field = createField(beanProperty, propertyTypeDef);
        classDefBuilder.addField(field);
        String propertyName = beanProperty.getSimpleName();
        classDefBuilder.addMethod(MethodDef.builder(propertyName)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(propertyName, propertyTypeDef)
            .build((self, parameterDefs) -> StatementDef.multi(
                self.field(field).assign(parameterDefs.get(0)),
                returningExpressionProvider.apply(self)
            )));
    }

    private static FieldDef createField(PropertyElement beanProperty, TypeDef type) {
        TypeDef fieldType = type.makeNullable();
        if (!fieldType.isNullable()) {
            throw new IllegalStateException("Could not make the field nullable");
        }
        FieldDef.FieldDefBuilder fieldDef = FieldDef.builder(beanProperty.getSimpleName())
            .ofType(fieldType)
            .addModifiers(Modifier.PROTECTED);
        try {
            beanProperty.stringValue(Bindable.class, "defaultValue").ifPresent(defaultValue ->
                fieldDef.initializer(ExpressionDef.constant(beanProperty.getType(), fieldType, defaultValue))
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid or unsupported default value specified: " + beanProperty.stringValue(Bindable.class, "defaultValue").orElse(null));
        }
        return fieldDef.build();
    }

    private static void createSingularPropertyMethods(ClassDef.ClassDefBuilder classBuilder,
                                                      PropertyElement beanProperty,
                                                      Function<VariableDef.This, StatementDef> returningExpressionProvider) {
        String propertyName = beanProperty.getSimpleName();
        String singularName = beanProperty.stringValue(Singular.class).orElse(null);
        if (singularName == null) {
            singularName = singularize(propertyName);
            if (singularName == null) {
                throw new IllegalStateException("Cannot determine singular name for property: " + beanProperty.getName() + ". Please specify a singular name: @Singular(\"singularName\")");
            }
        }
        if (beanProperty.getType().isAssignable(Iterable.class)) {
            TypeDef singularTypeDef = beanProperty.getType().getFirstTypeArgument().<TypeDef>map(ClassTypeDef::of).orElse(TypeDef.OBJECT);
            TypeDef fieldType = TypeDef.parameterized(ArrayList.class, singularTypeDef);
            FieldDef field = createField(beanProperty, fieldType);
            classBuilder.addField(field);
            classBuilder.addMethod(MethodDef.builder(propertyName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(propertyName, TypeDef.parameterized(Collection.class, singularTypeDef))
                .build((self, parameterDefs) -> StatementDef.multi(
                    parameterDefs.get(0).isNull().asConditionIf(
                        ClassTypeDef.of(NullPointerException.class).doThrow(ExpressionDef.constant(propertyName + " cannot be null"))
                    ),
                    self.field(field).isNull().asConditionIf(
                        self.field(field).assign(ClassTypeDef.of(ArrayList.class).instantiate())
                    ),
                    self.field(field).invoke("addAll", TypeDef.primitive(boolean.class), parameterDefs.get(0)),
                    returningExpressionProvider.apply(self)
                )));
            classBuilder.addMethod(MethodDef.builder(singularName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(singularName, singularTypeDef)
                .build((self, parameterDefs) -> StatementDef.multi(
                    self.field(field).isNull().asConditionIf(
                        self.field(field).assign(ClassTypeDef.of(ArrayList.class).instantiate())
                    ),
                    self.field(field).invoke("add", TypeDef.of(boolean.class), parameterDefs.get(0)),
                    returningExpressionProvider.apply(self)
                )));
            classBuilder.addMethod(MethodDef.builder("clear" + StringUtils.capitalize(propertyName))
                .addModifiers(Modifier.PUBLIC)
                .build((self, parameterDefs) -> StatementDef.multi(
                    self.field(field).isNonNull().asConditionIf(
                        self.field(field).invoke("clear", TypeDef.VOID)
                    ),
                    returningExpressionProvider.apply(self)
                )));
        } else if (beanProperty.getType().isAssignable(Map.class)) {
            TypeDef keyType = beanProperty.getType().getFirstTypeArgument().<TypeDef>map(ClassTypeDef::of).orElse(TypeDef.OBJECT);
            TypeDef valueType = beanProperty.getType().getTypeArguments().values().stream().skip(1).findFirst().<TypeDef>map(ClassTypeDef::of).orElse(TypeDef.OBJECT);
            ClassTypeDef mapEntryType = TypeDef.parameterized(
                Map.Entry.class,
                keyType,
                valueType
            );
            ClassTypeDef fieldType = TypeDef.parameterized(ArrayList.class, mapEntryType);
            FieldDef field = createField(beanProperty, fieldType);
            classBuilder.addField(field);
            classBuilder.addMethod(MethodDef.builder(propertyName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(propertyName, TypeDef.parameterized(Map.class, keyType, valueType))
                .build((self, parameterDefs) -> StatementDef.multi(
                    parameterDefs.get(0).isNull().asConditionIf(
                        ClassTypeDef.of(NullPointerException.class).doThrow(ExpressionDef.constant(propertyName + " cannot be null"))
                    ),
                    self.field(field).isNull().asConditionIf(
                        self.field(field).assign(fieldType.instantiate())
                    ),
                    self.field(field).invoke(
                        "addAll",
                        TypeDef.primitive(boolean.class),
                        parameterDefs.get(0).invoke("entrySet", ClassTypeDef.of(Set.class))
                    ),
                    returningExpressionProvider.apply(self)
                )));
            classBuilder.addMethod(MethodDef.builder(singularName)
                .addModifiers(Modifier.PUBLIC)
                .addParameter("key", keyType)
                .addParameter("value", valueType)
                .build((self, parameterDefs) -> StatementDef.multi(
                    self.field(field).isNull().asConditionIf(
                        self.field(field).assign(TypeDef.parameterized(ArrayList.class, TypeDef.parameterized(Map.Entry.class, keyType, valueType)).instantiate())
                    ),
                    self.field(field).invoke(
                        "add",
                        TypeDef.of(boolean.class),
                        ClassTypeDef.of(Map.class).invokeStatic(
                            "entry",
                            ClassTypeDef.of(Map.Entry.class),
                            parameterDefs.get(0),
                            parameterDefs.get(1)
                        )
                    ),
                    returningExpressionProvider.apply(self)
                )));
            classBuilder.addMethod(MethodDef.builder("clear" + StringUtils.capitalize(propertyName))
                .addModifiers(Modifier.PUBLIC)
                .build((self, parameterDefs) -> StatementDef.multi(
                    self.field(field).isNonNull().asConditionIf(
                        self.field(field).invoke("clear", TypeDef.VOID)
                    ),
                    returningExpressionProvider.apply(self)
                )));
        } else {
            throw new IllegalStateException("Unsupported singular collection type [" + beanProperty.getType().getName() + "] for property: " + beanProperty.getName());
        }
    }

    static MethodDef createBuildMethod(ClassElement producedType) {
        return MethodDef.builder("build")
            .addModifiers(Modifier.PUBLIC)
            .build((self, parameterDefs) -> {
                MethodElement constructorElement = producedType.getPrimaryConstructor()
                    .filter(c -> !c.isPrivate())
                    .or(producedType::getDefaultConstructor)
                    .orElse(null);

                List<PropertyElement> beanProperties = new ArrayList<>(producedType.getBeanProperties());
                List<ExpressionDef> values = new ArrayList<>();
                if (constructorElement != null) {
                    for (ParameterElement parameter : constructorElement.getParameters()) {
                        PropertyElement propertyElement = beanProperties.stream().filter(p -> p.getName().equals(parameter.getName())).findFirst().orElse(null);
                        if (propertyElement != null) {
                            beanProperties.remove(propertyElement);
                        }
                        // We need to convert it for the correct type in Kotlin
                        TypeDef fieldType = TypeDef.of(parameter.getType()).makeNullable();
                        VariableDef.Field field = self.field(parameter.getName(), fieldType);
                        values.add(
                            valueExpression(propertyElement, field).convert(TypeDef.of(parameter.getType()))
                        );
                    }
                }
                ClassTypeDef buildType = ClassTypeDef.of(producedType);
                if (beanProperties.isEmpty()) {
                    return buildType.instantiate(values).returning();
                }
                // Instantiate and set properties not assigned in the constructor
                return buildType.instantiate(values).newLocal("instance", instanceVar -> {
                    List<StatementDef> statements = new ArrayList<>();
                    for (PropertyElement beanProperty : beanProperties) {
                        Optional<MethodElement> writeMethod = beanProperty.getWriteMethod();
                        if (writeMethod.isPresent()) {
                            String propertyName = beanProperty.getSimpleName();
                            TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
                            statements.add(
                                instanceVar.invoke(writeMethod.get(), valueExpression(beanProperty, self.field(propertyName, propertyTypeDef)))
                            );
                        }
                    }
                    statements.add(instanceVar.returning());
                    return StatementDef.multi(statements);
                });
            });
    }

    private static ExpressionDef valueExpression(@Nullable PropertyElement propertyElement,
                                                 VariableDef.Field field) {
        if (propertyElement != null && propertyElement.hasAnnotation(Singular.class)) {
            return singularValueExpression(propertyElement, field);
        }
        return field;
    }

    private static ExpressionDef singularValueExpression(PropertyElement propertyElement,
                                                         VariableDef.Field field) {
        String collectionType = propertyElement.getType().getName();
        ClassTypeDef elementType = propertyElement.getType().getFirstTypeArgument().map(ClassTypeDef::of).orElse(ClassTypeDef.OBJECT);
        TypeDef propertyType = TypeDef.of(propertyElement.getType());
        ExpressionDef collectionSize = field.isNull().asConditionIfElse(
            ExpressionDef.constant(0),
            field.invoke("size", TypeDef.primitive(int.class))
        );
        if (collectionType.equals(List.class.getName()) || collectionType.equals(Collection.class.getName()) || collectionType.equals(Iterable.class.getName())) {
            ClassTypeDef javaListType = ClassTypeDef.of(List.class);
            return
                collectionSize.asExpressionSwitch(
                    propertyType,
                    Map.of(
                        // List.of()
                        ExpressionDef.constant(0), javaListType.invokeStatic("of", javaListType),
                        // List.of(single)
                        ExpressionDef.constant(1), javaListType.invokeStatic("of", javaListType, field.invoke("get", elementType, ExpressionDef.constant(0))),
                        // List.copyOf(all)
                        ExpressionDef.nullValue(), javaListType.invokeStatic("copyOf", javaListType, field)
                    )
                );
        } else if (collectionType.equals(Set.class.getName())) {
            ClassTypeDef setListType = ClassTypeDef.of(Set.class);
            return
                collectionSize.asExpressionSwitch(
                    propertyType,
                    Map.of(
                        // Set.of()
                        ExpressionDef.constant(0), setListType.invokeStatic("of", setListType),
                        // Set.of(single)
                        ExpressionDef.constant(1), setListType.invokeStatic("of", setListType, field.invoke("get", elementType, ExpressionDef.constant(0))),
                        // Collections.unmodifiableSet(new LinkedHashSet(all))
                        ExpressionDef.nullValue(), ClassTypeDef.of(Collections.class)
                            .invokeStatic("unmodifiableSet", propertyType, ClassTypeDef.of(LinkedHashSet.class).instantiate(field))
                    )
                );
        } else if (collectionType.equals(SortedSet.class.getName())) {
            return collectionSize.asExpressionSwitch(
                propertyType,
                Map.of(
                    // Collections.emptySortedSet()
                    ExpressionDef.constant(0), ClassTypeDef.of(Collections.class).invokeStatic("emptySortedSet", propertyType),
                    // Collections.unmodifiableSortedSet(new TreeSet(all))
                    ExpressionDef.nullValue(), ClassTypeDef.of(Collections.class)
                        .invokeStatic("unmodifiableSortedSet", propertyType, TypeDef.parameterized(TreeSet.class, elementType).instantiate(field))
                )
            );
        } else if (collectionType.equals(Map.class.getName())) {
            return collectionSize.asExpressionSwitch(
                propertyType,
                Map.of(
                    // Map.of
                    ExpressionDef.constant(0), ClassTypeDef.of(Map.class).invokeStatic("of", propertyType),
                    // Create and fill the map
                    ExpressionDef.nullValue(), new ExpressionDef.SwitchYieldCase(
                        propertyType,
                        createMapStatement(propertyElement, field, LinkedHashMap.class, "unmodifiableMap")
                    )
                ));
        } else if (collectionType.equals(SortedMap.class.getName())) {
            return collectionSize.asExpressionSwitch(
                propertyType,
                Map.of(
                    // Collections.emptySortedMap
                    ExpressionDef.constant(0), ClassTypeDef.of(Collections.class).invokeStatic("emptySortedMap", propertyType),
                    // Create and fill the map
                    ExpressionDef.nullValue(), new ExpressionDef.SwitchYieldCase(
                        propertyType,
                        createMapStatement(propertyElement, field, TreeMap.class, "unmodifiableSortedMap")
                    )
                ));
        } else {
            throw new IllegalStateException("Unsupported singular collection type [" + collectionType + "] for property: " + propertyElement.getName());
        }
    }

    private static StatementDef createMapStatement(PropertyElement propertyElement,
                                                   VariableDef.Field field,
                                                   Class<?> mapClass,
                                                   String unmodifiableMethodName) {
        ClassElement propertyType = propertyElement.getType();
        TypeDef keyType = propertyType.getFirstTypeArgument().<TypeDef>map(ClassTypeDef::of).orElse(TypeDef.OBJECT);
        TypeDef valueType = propertyType.getTypeArguments().values().stream().skip(1).findFirst().<TypeDef>map(ClassTypeDef::of).orElse(TypeDef.OBJECT);
        ClassTypeDef entryType = TypeDef.parameterized(Map.Entry.class, keyType, valueType);
        return TypeDef.parameterized(mapClass, keyType, valueType)
            .instantiate()
            .newLocal(field.name() + "Map", mapVar ->
                field.invoke("iterator", TypeDef.parameterized(Iterator.class, entryType))
                    .newLocal(field.name() + "Iterator", iteratorVar ->
                        iteratorVar.invoke("hasNext", TypeDef.primitive(boolean.class)).whileLoop(
                            iteratorVar.invoke("next", entryType).newLocal(field.name() + "Entry", entryVar ->
                                mapVar.invoke("put", TypeDef.of(boolean.class),
                                    entryVar.invoke("getKey", keyType),
                                    entryVar.invoke("getValue", valueType)
                                ))
                        ).after(
                            ClassTypeDef.of(Collections.class).invokeStatic(unmodifiableMethodName, ClassTypeDef.of(propertyType), mapVar)
                                .returning()
                        )
                    ));

    }

}
