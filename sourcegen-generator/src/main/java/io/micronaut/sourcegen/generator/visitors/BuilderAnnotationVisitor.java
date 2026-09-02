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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    private static final String BUILDER_ANNOTATED_WITH_MEMBER = "annotatedWith";
    private static final String CLEAR_METHOD = "clear";

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
            List<PropertyElement> properties = element.getBeanProperties();
            @NonNull ParameterElement[] constructorElement = element.getPrimaryConstructor()
                .filter(c -> !c.isPrivate())
                .or(element::getDefaultConstructor)
                .map(MethodElement::getParameters).orElse(ParameterElement.ZERO_PARAMETER_ELEMENTS);
            List<ParameterElement> constructorParameters = Arrays.asList(constructorElement);
            AnnotationValue<Builder> builderAnnotationValue = element.getAnnotation(Builder.class);
            ClassTypeDef elementType = ClassTypeDef.of(element);

            ClassDefBuilder builder = createBuilder(
                element.getPackageName(),
                elementType,
                builderAnnotationValue,
                properties,
                constructorParameters
            );
            ClassDef builderDef = builder.build();

            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
            if (sourceGenerator == null) {
                return;
            }

            processed.add(element.getName());
            sourceGenerator.write(builderDef, context, element);
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

    /**
     * Create a builder for the given arguments.
     *
     * @param packageName            The package name
     * @param elementType            The element type
     * @param builderAnnotationValue The builder annotation value.
     * @param properties             The properties
     * @param constructorParameters  The constructor parameters
     * @return A class definition builder for the builder
     */
    static @NonNull ClassDefBuilder createBuilder(
        String packageName,
        @NonNull ClassTypeDef elementType,
        @Nullable AnnotationValue<Builder> builderAnnotationValue,
        @NonNull List<PropertyElement> properties,
        @NonNull List<ParameterElement> constructorParameters) {
        Function<BuilderGenerator.BuildContext, StatementDef> returnSelf = (context) -> context.aThis().returning();
        return createBuilder(packageName, elementType, builderAnnotationValue, properties, constructorParameters, returnSelf);
    }

    /**
     * Create a builder for the given arguments.
     *
     * @param packageName            The package name
     * @param elementType            The element type
     * @param builderAnnotationValue The builder annotation value.
     * @param properties             The properties
     * @param constructorParameters  The constructor parameters
     * @param buildReturnStatement   The return statement to use for building.
     * @return A class definition builder for the builder
     */
    static ClassDefBuilder createBuilder(
        String packageName,
        ClassTypeDef elementType,
        @Nullable AnnotationValue<Builder> builderAnnotationValue,
        List<PropertyElement> properties,
        List<ParameterElement> constructorParameters,
        Function<BuilderGenerator.BuildContext, StatementDef> buildReturnStatement) {
        String localBinaryName = elementType.getName().startsWith(packageName + ".")
            ? elementType.getName().substring(packageName.isEmpty() ? 0 : packageName.length() + 1)
            : elementType.getName();
        String baseName = elementType.isInner() ? localBinaryName.replace("$", "") : elementType.getSimpleName();
        String builderSimpleName = baseName + "Builder";
        String builderClassName = packageName + "." + builderSimpleName;
        List<TypeDef.TypeVariable> typeArguments = List.of();
        if (elementType instanceof ClassTypeDef.Parameterized parameterizedType) {
            typeArguments = parameterizedType.typeArguments()
                .stream().filter(td -> td instanceof TypeDef.TypeVariable)
                .map(TypeDef.TypeVariable.class::cast)
                .toList();
        }

        ClassTypeDef builderType;

        if (typeArguments.isEmpty()) {
            builderType = ClassTypeDef.of(builderClassName);
        } else {
            builderType = TypeDef.parameterized(
                ClassTypeDef.of(builderClassName),
                typeArguments.toArray(TypeDef[]::new)
            );
        }

        ClassDefBuilder builder = ClassDef.builder(builderClassName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        for (TypeDef.TypeVariable typeArgument : typeArguments) {
            builder.addTypeVariable(typeArgument);
        }
        if (builderAnnotationValue != null) {
            addAnnotations(builder, builderAnnotationValue);
        }

        for (PropertyElement beanProperty : properties) {
            createModifyPropertyMethod(builder, builderType, beanProperty, buildReturnStatement);
        }

        builder.addMethod(MethodDef.constructor().build());
        if (!properties.isEmpty()) {
            builder.addMethod(createAllPropertiesConstructor(properties));
        }

        builder.addMethod(createBuilderMethod(builderType));

        builder.addMethod(createBuildMethod(
            elementType,
            properties,
            constructorParameters)
        );
        return builder;
    }

    static void addAnnotations(ClassDefBuilder builder, @Nullable AnnotationValue<?> annotation) {
        if (annotation == null) {
            return;
        }
        Optional<AnnotationClassValue[]> annotatedWith = annotation.getConvertibleValues()
            .get(BUILDER_ANNOTATED_WITH_MEMBER, AnnotationClassValue[].class);
        if (annotatedWith.isEmpty()) {
            // Apply the default annotation
            builder.addAnnotation(Introspected.class);
        } else {
            for (AnnotationClassValue<?> value : annotatedWith.get()) {
                builder.addAnnotation(value.getName());
            }
        }
    }

    static MethodDef createAllPropertiesConstructor(List<PropertyElement> properties) {
        MethodDef.MethodDefBuilder builder = MethodDef.constructor()
            .addAnnotation(Creator.class);
        int index = 0;
        for (PropertyElement parameter : properties) {
            int parameterIndex = index++;
            builder.addParameter(ParameterDef.of(parameter.getName(), TypeDef.of(parameter.getType())));
            builder.addStatement((aThis, methodParameters) -> {
                VariableDef.MethodParameter methodParameter = methodParameters.get(parameterIndex);
                VariableDef.Field propertyField = aThis.field(methodParameter.name(), builderFieldType(parameter));
                if (parameter.hasAnnotation(Singular.class)) {
                    if (parameter.getType().getName().equals(Iterable.class.getName())) {
                        return iterableToArrayListStatement(propertyField, methodParameter);
                    } else if (parameter.getType().isAssignable(Map.class)) {
                        return mapToArrayListStatement(propertyField, methodParameter);
                    } else {
                        return propertyField.put(ClassTypeDef.of(ArrayList.class).instantiate(methodParameter));
                    }
                } else {
                    return propertyField.put(methodParameter);
                }
            });
        }
        return builder.build();
    }

    private static StatementDef iterableToArrayListStatement(VariableDef.Field propertyField,
                                                             VariableDef.MethodParameter parameter) {
        return ClassTypeDef.of(ArrayList.class)
            .instantiate()
            .newLocal(parameter.name() + "ArrayList", arrayListVar ->
                parameter
                    .invoke("iterator", ClassTypeDef.of(Iterator.class))
                    .newLocal(parameter.name() + "Iterator", iteratorVar ->
                        parameter.ifNonNull(
                                iteratorVar.invoke("hasNext", TypeDef.primitive(boolean.class))
                                    .whileLoop(
                                        arrayListVar.invoke("add", TypeDef.of(boolean.class), iteratorVar.invoke("next", ClassTypeDef.OBJECT))
                                    )
                            )
                            .after(
                                propertyField.assign(arrayListVar)
                            )));
    }

    private static StatementDef mapToArrayListStatement(VariableDef.Field propertyField,
                                                        VariableDef.MethodParameter parameter) {
        return propertyField.put(
            ClassTypeDef.of(ArrayList.class).instantiate(
                parameter.invoke("entrySet", ClassTypeDef.of(Set.class))
            )
        );
    }

    private static MethodDef createBuilderMethod(ClassTypeDef builderType) {

        ClassTypeDef erasure = ClassTypeDef.of(builderType.getCanonicalName());
        MethodDef.MethodDefBuilder methodBuilder = MethodDef.builder("builder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addStatement(erasure.instantiate().returning());
        if (builderType instanceof ClassTypeDef.Parameterized parameterized) {
            List<TypeDef> typeDefs = parameterized.typeArguments();
            List<TypeDef> resolvedTypeDefs = new ArrayList<>();
            for (TypeDef typeDef : typeDefs) {
                if (typeDef instanceof TypeDef.TypeVariable variable) {
                    TypeDef.TypeVariable newTypeDef = new TypeDef.TypeVariable(
                        variable.name() + "S",
                        variable.bounds(),
                        variable.nullable()
                    );
                    resolvedTypeDefs.add(newTypeDef);
                    methodBuilder.addTypeVariable(
                        newTypeDef
                    );
                }
            }

            methodBuilder.returns(
                TypeDef.parameterized(
                    parameterized.rawType(),
                    resolvedTypeDefs.toArray(TypeDef[]::new)
                )
            );
        } else {
            methodBuilder.returns(builderType);
        }
        return methodBuilder
            .build();
    }

    static List<MethodDef> createModifyPropertyMethod(ClassDefBuilder classDefBuilder,
                                                      ClassTypeDef builderType, PropertyElement beanProperty,
                                                      Function<BuilderGenerator.BuildContext, StatementDef> returningExpressionProvider) {
        return createModifyPropertyMethod(classDefBuilder, builderType, null, false, beanProperty, returningExpressionProvider);
    }

    /**
     * Add the methods that assign the given property to the builder being created.
     *
     * @param classDefBuilder             The builder being created
     * @param builderType                 The type of the builder being created
     * @param returnType                  The type the methods should declare as their return type, or
     *                                    null to keep the default of returning the builder itself
     * @param overrides                   True if the methods override the declarations of a supertype
     * @param beanProperty                The property to assign
     * @param returningExpressionProvider The return statement of the methods
     * @return The methods that were added
     */
    static List<MethodDef> createModifyPropertyMethod(ClassDefBuilder classDefBuilder,
                                                      ClassTypeDef builderType,
                                                      @Nullable TypeDef returnType,
                                                      boolean overrides,
                                                      PropertyElement beanProperty,
                                                      Function<BuilderGenerator.BuildContext, StatementDef> returningExpressionProvider) {
        List<MethodDef> methods;
        if (beanProperty.hasAnnotation(Singular.class)) {
            methods = createSingularPropertyMethods(classDefBuilder, returnType, overrides, beanProperty, returningExpressionProvider);
        } else {
            methods = List.of(createDefaultModifyPropertyMethod(classDefBuilder, builderType, returnType, overrides, beanProperty, returningExpressionProvider));
        }
        methods.forEach(classDefBuilder::addMethod);
        return methods;
    }

    private static MethodDef createDefaultModifyPropertyMethod(ClassDefBuilder classDefBuilder,
                                                               ClassTypeDef builderType,
                                                               @Nullable TypeDef returnType,
                                                               boolean overrides,
                                                               PropertyElement beanProperty,
                                                               Function<BuilderGenerator.BuildContext, StatementDef> returningExpressionProvider) {
        TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
        FieldDef field = createField(beanProperty, propertyTypeDef);
        classDefBuilder.addField(field);
        String propertyName = beanProperty.getSimpleName();
        MethodDef.MethodDefBuilder methodDef = MethodDef.builder(propertyName)
            .addModifiers(Modifier.PUBLIC)
            .overrides(overrides)
            .addParameter(propertyName, propertyTypeDef);
        if (returnType != null) {
            methodDef.returns(returnType);
        } else if (builderType instanceof ClassTypeDef.Parameterized) {
            methodDef.returns(builderType);
        }
        return methodDef
            .build((self, parameterDefs) -> StatementDef.multi(
                self.field(field).assign(parameterDefs.get(0)),
                returningExpressionProvider.apply(new BuilderGenerator.BuildContext(self, field))
            ));
    }

    /**
     * The type of the field the builder keeps a property in. It is nullable, so a property that was never set
     * can be told apart from one set to the type's default, and an {@link ArrayList} for a singular property,
     * which the builder accumulates into. A field has to be referred to by this type: a field access carries
     * its descriptor, so referring to the field by the property's own type writes one that cannot be resolved.
     *
     * @param beanProperty The property
     * @return The type of the field backing it
     */
    static TypeDef builderFieldType(PropertyElement beanProperty) {
        if (beanProperty.hasAnnotation(Singular.class)) {
            ClassElement propertyType = beanProperty.getType();
            if (propertyType.isAssignable(Iterable.class)) {
                return singularIterableFieldType(propertyType).makeNullable();
            }
            if (propertyType.isAssignable(Map.class)) {
                return singularMapFieldType(propertyType).makeNullable();
            }
        }
        return TypeDef.of(beanProperty.getType()).makeNullable();
    }

    private static ClassTypeDef singularIterableFieldType(ClassElement propertyType) {
        return TypeDef.parameterized(ArrayList.class, singularElementType(propertyType));
    }

    private static ClassTypeDef singularMapFieldType(ClassElement propertyType) {
        return TypeDef.parameterized(ArrayList.class, singularMapEntryType(propertyType));
    }

    private static TypeDef singularElementType(ClassElement propertyType) {
        return propertyType.getFirstTypeArgument().<TypeDef>map(ClassTypeDef::of).orElse(TypeDef.OBJECT);
    }

    private static ClassTypeDef singularMapEntryType(ClassElement propertyType) {
        return TypeDef.parameterized(
            Map.Entry.class,
            singularElementType(propertyType),
            propertyType.getTypeArguments().values().stream().skip(1).findFirst().<TypeDef>map(ClassTypeDef::of).orElse(TypeDef.OBJECT)
        );
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

    private static List<MethodDef> createSingularPropertyMethods(ClassDef.ClassDefBuilder classBuilder,
                                                                 @Nullable TypeDef returnType,
                                                                 boolean overrides,
                                                                 PropertyElement beanProperty,
                                                                 Function<BuilderGenerator.BuildContext, StatementDef> returningExpressionProvider) {
        String propertyName = beanProperty.getSimpleName();
        String singularName = beanProperty.stringValue(Singular.class).orElse(null);
        if (singularName == null) {
            singularName = singularize(propertyName);
            if (singularName == null) {
                throw new IllegalStateException("Cannot determine singular name for property: " + beanProperty.getName() + ". Please specify a singular name: @Singular(\"singularName\")");
            }
        }
        List<MethodDef> methods = new ArrayList<>(3);
        if (beanProperty.getType().isAssignable(Iterable.class)) {
            TypeDef singularTypeDef = singularElementType(beanProperty.getType());
            FieldDef field = createField(beanProperty, singularIterableFieldType(beanProperty.getType()));
            classBuilder.addField(field);
            methods.add(singularMethod(propertyName, returnType, overrides)
                .addParameter(propertyName, TypeDef.parameterized(Collection.class, singularTypeDef))
                .build((self, parameterDefs) -> StatementDef.multi(
                    parameterDefs.get(0).isNull().doIf(
                        ClassTypeDef.of(NullPointerException.class)
                            .instantiate(ExpressionDef.constant(propertyName + " cannot be null"))
                            .doThrow()
                    ),
                    self.field(field).isNull().doIf(
                        self.field(field).assign(ClassTypeDef.of(ArrayList.class).instantiate())
                    ),
                    self.field(field).invoke("addAll", TypeDef.primitive(boolean.class), parameterDefs.get(0)),
                    returningExpressionProvider.apply(new BuilderGenerator.BuildContext(self, field))
                )));
            methods.add(singularMethod(singularName, returnType, overrides)
                .addParameter(singularName, singularTypeDef)
                .build((self, parameterDefs) -> StatementDef.multi(
                    self.field(field).isNull().doIf(
                        self.field(field).assign(ClassTypeDef.of(ArrayList.class).instantiate())
                    ),
                    self.field(field).invoke("add", TypeDef.of(boolean.class), parameterDefs.get(0)),
                    returningExpressionProvider.apply(new BuilderGenerator.BuildContext(self, field))
                )));
            methods.add(singularMethod(CLEAR_METHOD + StringUtils.capitalize(propertyName), returnType, overrides)
                .build((self, parameterDefs) -> StatementDef.multi(
                    self.field(field).isNonNull().doIf(
                        self.field(field).invoke(CLEAR_METHOD, TypeDef.VOID)
                    ),
                    returningExpressionProvider.apply(new BuilderGenerator.BuildContext(self, field))
                )));
        } else if (beanProperty.getType().isAssignable(Map.class)) {
            TypeDef keyType = singularElementType(beanProperty.getType());
            TypeDef valueType = beanProperty.getType().getTypeArguments().values().stream().skip(1).findFirst().<TypeDef>map(ClassTypeDef::of).orElse(TypeDef.OBJECT);
            ClassTypeDef fieldType = singularMapFieldType(beanProperty.getType());
            FieldDef field = createField(beanProperty, fieldType);
            classBuilder.addField(field);
            methods.add(singularMethod(propertyName, returnType, overrides)
                .addParameter(propertyName, TypeDef.parameterized(Map.class, keyType, valueType))
                .build((self, parameterDefs) -> StatementDef.multi(
                    parameterDefs.get(0).isNull().doIf(
                        ClassTypeDef.of(NullPointerException.class)
                            .instantiate(ExpressionDef.constant(propertyName + " cannot be null"))
                            .doThrow()
                    ),
                    self.field(field).isNull().doIf(
                        self.field(field).assign(fieldType.instantiate())
                    ),
                    self.field(field).invoke(
                        "addAll",
                        TypeDef.primitive(boolean.class),
                        parameterDefs.get(0).invoke("entrySet", ClassTypeDef.of(Set.class))
                    ),
                    returningExpressionProvider.apply(new BuilderGenerator.BuildContext(self, field))
                )));
            methods.add(singularMethod(singularName, returnType, overrides)
                .addParameter("key", keyType)
                .addParameter("value", valueType)
                .build((self, parameterDefs) -> StatementDef.multi(
                    self.field(field).isNull().doIf(
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
                    returningExpressionProvider.apply(new BuilderGenerator.BuildContext(self, field))
                )));
            methods.add(singularMethod(CLEAR_METHOD + StringUtils.capitalize(propertyName), returnType, overrides)
                .build((self, parameterDefs) -> StatementDef.multi(
                    self.field(field).isNonNull().doIf(
                        self.field(field).invoke(CLEAR_METHOD, TypeDef.VOID)
                    ),
                    returningExpressionProvider.apply(new BuilderGenerator.BuildContext(self, field))
                )));
        } else {
            throw new IllegalStateException("Unsupported singular collection type [" + beanProperty.getType().getName() + "] for property: " + beanProperty.getName());
        }
        return methods;
    }

    private static MethodDef.MethodDefBuilder singularMethod(String name, @Nullable TypeDef returnType, boolean overrides) {
        MethodDef.MethodDefBuilder methodBuilder = MethodDef.builder(name).addModifiers(Modifier.PUBLIC).overrides(overrides);
        if (returnType != null) {
            methodBuilder.returns(returnType);
        }
        return methodBuilder;
    }

    static MethodDef createBuildMethod(ClassTypeDef buildType, List<PropertyElement> properties, List<ParameterElement> constructorParameters) {
        return createBuildMethod(buildType, properties, constructorParameters, false);
    }

    /**
     * Create the build method of a builder.
     *
     * @param buildType             The type being built
     * @param properties            The properties
     * @param constructorParameters The constructor parameters
     * @param overrides             True if the method overrides the declaration of a supertype
     * @return The build method
     */
    static MethodDef createBuildMethod(ClassTypeDef buildType,
                                       List<PropertyElement> properties,
                                       List<ParameterElement> constructorParameters,
                                       boolean overrides) {
        return MethodDef.builder("build")
            .addModifiers(Modifier.PUBLIC)
            .overrides(overrides)
            .returns(buildType)
            .build((self, parameterDefs) -> {
                List<PropertyElement> remainingProperties = new ArrayList<>(properties);
                List<ExpressionDef> values = constructorValues(self, remainingProperties, constructorParameters);
                if (remainingProperties.isEmpty()) {
                    return buildType.instantiate(values).returning();
                }
                // Instantiate and set properties not assigned in the constructor
                return buildType.instantiate(values).newLocal("instance", instanceVar ->
                    StatementDef.multi(statements -> {
                        for (PropertyElement beanProperty : remainingProperties) {
                            beanProperty.getWriteMethod().ifPresent(writeMethod ->
                                statements.add(assignProperty(self, instanceVar, beanProperty, writeMethod))
                            );
                        }
                        return instanceVar.returning();
                    })
                );
            });
    }

    /**
     * The arguments of the constructor call the build method makes, taken from the fields the builder
     * keeps the corresponding properties in. The properties assigned this way are removed from the
     * given properties, leaving the ones the build method has to assign through a setter.
     *
     * @param self                  The builder instance
     * @param properties            The properties, which the assigned ones are removed from
     * @param constructorParameters The constructor parameters
     * @return The constructor arguments
     */
    private static List<ExpressionDef> constructorValues(VariableDef.This self,
                                                         List<PropertyElement> properties,
                                                         List<ParameterElement> constructorParameters) {
        List<ExpressionDef> values = new ArrayList<>();
        for (ParameterElement parameter : constructorParameters) {
            PropertyElement propertyElement = properties.stream().filter(p -> p.getName().equals(parameter.getName())).findFirst().orElse(null);
            if (propertyElement != null) {
                properties.remove(propertyElement);
            }
            // We need to convert it for the correct type in Kotlin
            TypeDef parameterType = TypeDef.of(parameter.getType());
            TypeDef nullableFieldType = propertyElement == null ? parameterType.makeNullable() : builderFieldType(propertyElement);
            VariableDef.Field field = self.field(parameter.getName(), nullableFieldType);
            values.add(!parameterType.isPrimitive() ?
                valueExpression(propertyElement, field).cast(parameterType) :
                field.ifNull(TypeDef.Primitive.defaultValue(parameter.getType().getName()), valueExpression(propertyElement, field)).cast(parameterType));
        }
        return values;
    }

    /**
     * Assign a property the constructor does not take through its setter. A property the builder can
     * keep unassigned is only assigned when it was, so that the value the constructor gave it is kept.
     *
     * @param self         The builder instance
     * @param instanceVar  The instance being built
     * @param beanProperty The property to assign
     * @param writeMethod  The setter of the property
     * @return The statement assigning it
     */
    private static StatementDef assignProperty(VariableDef.This self,
                                               VariableDef instanceVar,
                                               PropertyElement beanProperty,
                                               MethodElement writeMethod) {
        TypeDef propertyType = TypeDef.of(beanProperty.getType());
        TypeDef fieldType = builderFieldType(beanProperty);
        VariableDef.Field field = self.field(beanProperty.getSimpleName(), fieldType);
        StatementDef assign = instanceVar.invoke(
            writeMethod,
            valueExpression(beanProperty, field).cast(propertyType)
        );
        return fieldType.isNullable() ? field.isNonNull().doIf(assign) : assign;
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
        ExpressionDef collectionSize = field.ifNull(
            ExpressionDef.primitiveConstant(0),
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
                        ExpressionDef.constant(1), javaListType.invokeStatic("of", javaListType, field.invoke("get", elementType, ExpressionDef.constant(0)))
                    ),
                    javaListType.invokeStatic("copyOf", javaListType, field)
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
                        ExpressionDef.constant(1), setListType.invokeStatic("of", setListType, field.invoke("get", elementType, ExpressionDef.constant(0)))
                    ),
                    // Collections.unmodifiableSet(new LinkedHashSet(all))
                    ClassTypeDef.of(Collections.class)
                        .invokeStatic("unmodifiableSet", propertyType, ClassTypeDef.of(LinkedHashSet.class).instantiate(field))
                );
        } else if (collectionType.equals(SortedSet.class.getName())) {
            return collectionSize.asExpressionSwitch(
                propertyType,
                Map.of(
                    // Collections.emptySortedSet()
                    ExpressionDef.constant(0), ClassTypeDef.of(Collections.class).invokeStatic("emptySortedSet", propertyType)
                ),
                // Collections.unmodifiableSortedSet(new TreeSet(all))
                ClassTypeDef.of(Collections.class)
                    .invokeStatic("unmodifiableSortedSet", propertyType, TypeDef.parameterized(TreeSet.class, elementType).instantiate(field))
            );
        } else if (collectionType.equals(Map.class.getName())) {
            return collectionSize.asExpressionSwitch(
                propertyType,
                Map.of(
                    // Map.of
                    ExpressionDef.constant(0), ClassTypeDef.of(Map.class).invokeStatic("of", propertyType)
                ),
                new ExpressionDef.SwitchYieldCase(
                    propertyType,
                    createMapStatement(propertyElement, field, LinkedHashMap.class, "unmodifiableMap")
                ));
        } else if (collectionType.equals(SortedMap.class.getName())) {
            return collectionSize.asExpressionSwitch(
                propertyType,
                Map.of(
                    // Collections.emptySortedMap
                    ExpressionDef.constant(0), ClassTypeDef.of(Collections.class).invokeStatic("emptySortedMap", propertyType)
                ),
                new ExpressionDef.SwitchYieldCase(
                    propertyType,
                    createMapStatement(propertyElement, field, TreeMap.class, "unmodifiableSortedMap")
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
                            iteratorVar.invoke("next", TypeDef.OBJECT).cast(Map.Entry.class).newLocal(field.name() + "Entry", entryVar ->
                                mapVar.invoke("put", TypeDef.of(boolean.class),
                                    entryVar.invoke("getKey", TypeDef.OBJECT).cast(keyType),
                                    entryVar.invoke("getValue", TypeDef.OBJECT).cast(valueType)
                                ))
                        ).after(
                            ClassTypeDef.of(Collections.class).invokeStatic(unmodifiableMethodName, ClassTypeDef.of(propertyType), mapVar)
                                .returning()
                        )
                    ));

    }

}
