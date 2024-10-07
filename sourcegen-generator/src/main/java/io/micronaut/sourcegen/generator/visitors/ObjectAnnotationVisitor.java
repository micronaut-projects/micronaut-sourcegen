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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.EqualsAndHashCode;
import io.micronaut.sourcegen.annotations.ToString;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The visitor that generates the Object class of a bean.
 * The Object class can have functions substituting toString, equals, and hashcode.
 * However, each method needs to be annotated to be generated.
 * {@link ToString} annotation for toString function
 * {@link EqualsAndHashCode} annotation for equals and hashCode functions
 *
 * @author Elif Kurtay
 * @since 1.3
 */

@Internal
public final class ObjectAnnotationVisitor implements TypeElementVisitor<Object, Object> {

    private static final int HASH_MULTIPLIER = 31;
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
        return Set.of(ToString.class.getName(), EqualsAndHashCode.class.getName());
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (!(element.hasStereotype(ToString.class) || element.hasStereotype(EqualsAndHashCode.class))) {
            return;
        }

        if (processed.contains(element.getName())) {
            return;
        }
        try {
            String simpleName = element.getSimpleName() + "Object";
            String objectClassName = element.getPackageName() + "." + simpleName;

            // class def and annotations
            ClassDef.ClassDefBuilder objectBuilder = ClassDef.builder(objectClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

            // create the utils functions if they are annotated
            if (element.hasStereotype(ToString.class)) {
                context.warn("@ToString annotation will only print out bean properties.", element);
                List<PropertyElement> filteredProperties = element.getBeanProperties().stream()
                    .filter(property -> !property.hasAnnotation(ToString.Exclude.class)).toList();
                createToStringMethod(objectBuilder, ClassTypeDef.of(element), filteredProperties);
            }
            if (element.hasStereotype(EqualsAndHashCode.class)) {
                List<PropertyElement> properties = element.getBeanProperties();
                createEqualsMethod(objectBuilder, ClassTypeDef.of(element), properties);
                createHashCodeMethod(objectBuilder, ClassTypeDef.of(element), properties);
            }

            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
            if (sourceGenerator == null) {
                return;
            }

            ClassDef objectDef = objectBuilder.build();
            processed.add(element.getName());
            context.visitGeneratedSourceFile(
                objectDef.getPackageName(),
                objectDef.getSimpleName(),
                element
            ).ifPresent(sourceFile -> {
                try {
                    sourceFile.write(
                        writer -> sourceGenerator.write(objectDef, writer)
                    );
                } catch (Exception e) {
                    throw new ProcessingException(element, "Failed to generate a ObjectBuilder: " + e.getMessage(), e);
                }
            });
        } catch (ProcessingException e) {
            throw e;
        } catch (Exception e) {
            SourceGenerators.handleFatalException(
                element,
                ToString.class,
                e,
                (exception -> {
                    processed.remove(element.getName());
                    throw exception;
                })
            );
        }
    }

    /*
    Creates a toString method with signature:
        public static String BeanNameObject.toString(BeanName object)
     */
    private static void createToStringMethod(ClassDef.ClassDefBuilder classDefBuilder, ClassTypeDef selfType, List<PropertyElement> properties) {
        MethodDef method = MethodDef.builder("toString")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeDef.STRING)
            .addParameter("instance", selfType)
            .build((self, parameterDef) ->
                ClassTypeDef.of(StringBuilder.class).instantiate(
                        ExpressionDef.constant(selfType.getSimpleName() + "["))
                    .newLocal("strBuilder", variableDef -> {
                        ExpressionDef exp = variableDef;
                        for (int i = 0; i < properties.size(); i++) {
                            var beanProperty = properties.get(i);
                            if (beanProperty.isWriteOnly()) {
                                continue;
                            }
                            ExpressionDef propertyValue = parameterDef.get(0).getPropertyValue(beanProperty);

                            exp = exp.invoke("append", variableDef.type(),
                                    ExpressionDef.constant(beanProperty.getName() + "="))
                                .invoke("append", variableDef.type(),
                                    TypeDef.of(beanProperty.getType()).isArray() ?
                                        ClassTypeDef.of(Arrays.class).invokeStatic("toString", TypeDef.STRING, propertyValue)
                                        : propertyValue
                                ).invoke("append", variableDef.type(),
                                    ExpressionDef.constant((i == properties.size() - 1) ? "]" : ", "));
                        }
                        return exp.invoke("toString", TypeDef.STRING).returning();
                    })
            );
        classDefBuilder.addMethod(method);
    }

    /*
    Creates an equals method with signature:
        public static boolean BeanNameObject.equals(BeanName object1, Object object2)
     */
    private static void createEqualsMethod(ClassDef.ClassDefBuilder classDefBuilder, ClassTypeDef selfType, List<PropertyElement> properties) {
        MethodDef method = MethodDef.builder("equals")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeDef.Primitive.BOOLEAN)
            .addParameter("instance", selfType)
            .addParameter("o", TypeDef.OBJECT.makeNullable())
            .build((self, parameterDef) -> {
                VariableDef instance = parameterDef.get(0);
                VariableDef o = parameterDef.get(1);

                return StatementDef.multi(
                    instance.equalsReferentially(o).asConditionIf(ExpressionDef.trueValue().returning()),
                    o.isNull().asConditionOr(
                            instance.invokeGetClass().asCondition(" != ", new ExpressionDef.InvokeGetClassMethod(o)))
                        .asConditionIf(ExpressionDef.falseValue().returning()),
                    o.cast(selfType).newLocal("other", variableDef -> {
                        ExpressionDef exp = null;
                        for (PropertyElement beanProperty : properties) {
                            if (beanProperty.hasAnnotation(EqualsAndHashCode.Exclude.class)) {
                                continue;
                            }
                            if (beanProperty.isWriteOnly()) {
                                continue;
                            }
                            var firstProperty = instance.getPropertyValue(beanProperty);
                            var secondProperty = variableDef.getPropertyValue(beanProperty);

                            ExpressionDef newEqualsExpression = new ExpressionDef.EqualsReferentially(firstProperty, secondProperty);
                            if (!beanProperty.isPrimitive() || beanProperty.isArray()) {
                                // Object.equals for objects
//                                if (beanProperty.isArray()) {
//                                    // Arrays.equals or Arrays.deepEquals for Array
//                                    String methodName = beanProperty.getArrayDimensions() > 1 ?  "deepEquals" : "equals";
//                                    equalsMethod = ClassTypeDef.of(Arrays.class).invokeStatic(methodName, TypeDef.Primitive.BOOLEAN, firstProperty, secondProperty);
//                                }
                                ExpressionDef equalsMethod = firstProperty.equalsStructurally(secondProperty);
                                newEqualsExpression = newEqualsExpression
                                    .asConditionOr(firstProperty.isNonNull().asConditionAnd(equalsMethod));
                            }

                            if (exp == null) {
                                exp = newEqualsExpression;
                            } else {
                                exp = exp.asConditionAnd(newEqualsExpression);
                            }
                        }
                        return Objects.requireNonNullElseGet(exp, ExpressionDef::trueValue).returning();
                    })
                );
            });
        classDefBuilder.addMethod(method);
    }

    /*
    Creates a hashCode method with signature:
        public static int BeanNameObject.hashCode(BeanName object)
     */
    private static void createHashCodeMethod(ClassDef.ClassDefBuilder classDefBuilder, ClassTypeDef selfType, List<PropertyElement> properties) {
        List<PropertyElement> props = properties.stream().filter(beanProperty -> !beanProperty.hasAnnotation(EqualsAndHashCode.Exclude.class) && !beanProperty.isWriteOnly()).toList();
        Iterator<PropertyElement> iterator = props.iterator();
        MethodDef method = MethodDef.builder("hashCode")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("instance", selfType.makeNullable())
            .returns(TypeDef.Primitive.INT)
            .build((self, parameterDef) -> {
                    if (!iterator.hasNext()) {
                        return ExpressionDef.constant(0).returning();
                    }
                    VariableDef.MethodParameter instance = parameterDef.get(0);
                PropertyElement propertyElement1 = iterator.next();
                return StatementDef.multi(
                        instance.isNull().asConditionIf(ExpressionDef.constant(0).returning()),
                        TypeDef.Primitive.INT.initialize(instance.getPropertyValue(propertyElement1).invokeHashCode()).newLocal("hashValue", hashValue -> {
                            List<StatementDef> hashUpdates = new ArrayList<>();
                            while (iterator.hasNext()) {
                                PropertyElement propertyElement = iterator.next();
                                ExpressionDef condition = hashValue.asCondition(" * ", ExpressionDef.constant(HASH_MULTIPLIER))
                                    .asCondition(" + ", instance.getPropertyValue(propertyElement).invokeHashCode());
                                hashUpdates.add(hashValue.assign(condition));
                            }
                            hashUpdates.add(hashValue.returning());
                            return StatementDef.multi(hashUpdates);
                        })
                    );
                }
            );
        classDefBuilder.addMethod(method);
    }

}
