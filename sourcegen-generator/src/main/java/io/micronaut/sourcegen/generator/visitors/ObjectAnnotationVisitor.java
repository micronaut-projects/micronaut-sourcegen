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

import io.micronaut.core.annotation.*;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.Builder;
import io.micronaut.sourcegen.annotations.EqualsAndHashCode;
import io.micronaut.sourcegen.annotations.Secret;
import io.micronaut.sourcegen.annotations.ToString;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.*;

import java.util.*;
import javax.lang.model.element.Modifier;

/**
 * The visitor that generates the Object class of a bean.
 * The Object class can have functions substituting toString, equals, and hashcode.
 * However, each method needs to be annotated to be generated.
 *      {@link ToString} annotation for toString function
 *      {@link EqualsAndHashCode} annotation for equals and hashCode functions
 *
 * @author Elif Kurtay
 * @since 1.3
 */

@Internal
public final class ObjectAnnotationVisitor implements TypeElementVisitor<Object, Object> {

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

            List<PropertyElement> properties = element.getBeanProperties();

            // create the utils functions if they are annotated
            if (element.hasStereotype(ToString.class)) {
                createToStringMethod(objectBuilder, element.getSimpleName(), properties);
            }
            if (element.hasStereotype(EqualsAndHashCode.class)) {
                createEqualsMethod(objectBuilder, element.getSimpleName(), properties);
                createHashCodeMethod(objectBuilder, element.getSimpleName(), properties);
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
    private static void createToStringMethod(ClassDef.ClassDefBuilder classDefBuilder, String objectName, List<PropertyElement> properties) {
        MethodDef method = MethodDef.builder("toString")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter("object", ClassTypeDef.of(objectName))
            .build((self, parameterDef) -> {
                List<StatementDef> statements = new ArrayList<>();
                VariableDef.Local strBuilder = new VariableDef.Local("strBuilder", ClassTypeDef.of(StringBuilder.class));

                statements.add(strBuilder.defineAndAssign(ClassTypeDef.of(StringBuilder.class).instantiate(ExpressionDef.constant(objectName + "["))));

                PropertyElement beanProperty;
                TypeDef propertyTypeDef;
                ExpressionDef thisProperty;
                for (int i = 0; i < properties.size(); i++) {
                    beanProperty = properties.get(i);
                    propertyTypeDef = TypeDef.of(beanProperty.getType());

                    // get property value
                    if (beanProperty.hasAnnotation(Secret.class)) {
                        thisProperty = ExpressionDef.constant("******");
                    } else if (beanProperty.getReadMethod().isPresent()) {
                        thisProperty = parameterDef.get(0).asVariable().invoke(
                            beanProperty.getReadMethod().get().getSimpleName(),
                            propertyTypeDef,
                            List.of()
                        );
                    } else {
                        continue;
                    }

                    statements.add(strBuilder.invoke(
                        "append",
                        ClassTypeDef.of(strBuilder.getClass()),
                        ExpressionDef.constant(beanProperty.getSimpleName() + "=")
                    ).invoke(
                        "append",
                        ClassTypeDef.of(strBuilder.getClass()),
                        (TypeDef.of(beanProperty.getType()) instanceof TypeDef.Array) ?
                            ClassTypeDef.of(Arrays.class).invokeStatic(
                                "toString",
                                TypeDef.of(String.class),
                                List.of(thisProperty))
                            :
                            thisProperty
                    ).invoke(
                        "append",
                        ClassTypeDef.of(strBuilder.getClass()),
                        ExpressionDef.constant((i == properties.size() - 1) ? "]" : ", ")
                    ));
                }
                statements.add(strBuilder.invoke("toString", TypeDef.of(String.class)).returning());
                return StatementDef.multi(statements);
            });
        classDefBuilder.addMethod(method);
    }

    /*
    Creates an equals method with signature:
        public static boolean BeanNameObject.equals(BeanName object1, Object object2)
     */
    private static void createEqualsMethod(ClassDef.ClassDefBuilder classDefBuilder, String objectName, List<PropertyElement> properties) {
        MethodDef method = MethodDef.builder("equals")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter("thisObject", ClassTypeDef.of(objectName))
            .addParameter("o", TypeDef.of(Object.class))
            .build((self, parameterDef) -> {
                // local variables needed
                VariableDef thisObject = parameterDef.get(0).asVariable();
                VariableDef o = parameterDef.get(1).asVariable();
                VariableDef.Local other = new VariableDef.Local("otherObject", ClassTypeDef.of(objectName));

                // property equal checks
                TypeDef propertyTypeDef;
                ExpressionDef.CallInstanceMethod firstProperty;
                ExpressionDef.CallInstanceMethod secondProperty;
                ExpressionDef propertyConditions = null;
                for (PropertyElement beanProperty : properties) {
                    propertyTypeDef = TypeDef.of(beanProperty.getType());
                    if (beanProperty.getReadMethod().isPresent()) {
                        firstProperty = thisObject.invoke(beanProperty.getReadMethod().get(), List.of());
                        secondProperty = other.invoke(beanProperty.getReadMethod().get(), List.of());
                    } else {
                        continue;
                    }

                    // equal check according to the properties' type
                    if (propertyTypeDef instanceof TypeDef.Primitive) {
                        if (propertyConditions == null) {
                            propertyConditions = firstProperty.asCondition(" == ", secondProperty);
                        } else {
                            propertyConditions = propertyConditions.asCondition(" && ", firstProperty.asCondition(" == ", secondProperty));
                        }
                    } else {
                        String methodName = (propertyTypeDef instanceof TypeDef.Array) ? "deepEquals" : "equals";
                        if (propertyConditions == null) {
                            propertyConditions = ClassTypeDef.of(Objects.class)
                                .invokeStatic(methodName, TypeDef.BOOLEAN, Arrays.asList(firstProperty, secondProperty));
                        } else {
                            propertyConditions = propertyConditions.asCondition(" && ", ClassTypeDef.of(Objects.class)
                                .invokeStatic(methodName, TypeDef.BOOLEAN, Arrays.asList(firstProperty, secondProperty)));
                        }
                    }
                }

                return StatementDef.multi(
                    thisObject.asCondition(" == ", o)
                        .asConditionIf(ExpressionDef.constant(true).returning()),
                    o.isNull().asCondition(" || ",
                        thisObject.invoke("getClass", ClassTypeDef.of("Class"))
                            .asCondition(" != ", o.invoke("getClass", ClassTypeDef.of("Class")))
                    ).asConditionIf(ExpressionDef.constant(false).returning()),
                    other.defineAndAssign(o.cast(ClassTypeDef.of(objectName))),
                    Objects.requireNonNullElseGet(propertyConditions, () -> ExpressionDef.constant(true)).returning()
                );
            });
        classDefBuilder.addMethod(method);
    }

    /*
    Creates a hashCode method with signature:
        public static int BeanNameObject.hashCode(BeanName object)
     */
    private static void createHashCodeMethod(ClassDef.ClassDefBuilder classDefBuilder, String objectName, List<PropertyElement> properties) {
        MethodDef method = MethodDef.builder("hashCode")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("object", ClassTypeDef.of(objectName))
            .returns(int.class)
            .build((self, parameterDef) -> {
                List<ExpressionDef> parameters = new ArrayList<>();
                TypeDef propertyTypeDef;
                ExpressionDef thisProperty;

                for (PropertyElement beanProperty : properties) {
                    propertyTypeDef = TypeDef.of(beanProperty.getType());
                    if (beanProperty.getReadMethod().isPresent()) {
                        thisProperty = parameterDef.get(0).asVariable()
                            .invoke(beanProperty.getReadMethod().get(), List.of());
                    } else {
                        continue;
                    }

                    // calculate new property hash value
                    if (propertyTypeDef instanceof TypeDef.Array) {
                        String methodName = (((TypeDef.Array) propertyTypeDef).dimensions() > 1) ?  "deepHashCode" : "hashCode";
                        thisProperty = ClassTypeDef.of(Arrays.class).invokeStatic(methodName, TypeDef.of(int.class), thisProperty);
                    }
                    parameters.add(thisProperty);
                }
                return ClassTypeDef.of(Objects.class).invokeStatic("hash", TypeDef.of(int.class), parameters).returning();
            });
        classDefBuilder.addMethod(method);
    }
}
