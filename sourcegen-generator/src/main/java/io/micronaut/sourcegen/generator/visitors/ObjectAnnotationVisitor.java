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
import io.micronaut.sourcegen.annotations.EqualsAndHashCode;
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

    private static final int NULL_HASH_VALUE = 43;
    private static final int TRUE_HASH_VALUE = 79;
    private static final int FALSE_HASH_VALUE = 97;
    private static final int HASH_MULTIPLIER = 59;
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
                context.warn("@ToString annotation will only print out bean properties.", element);
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
            .addParameter("instance", ClassTypeDef.of(objectName))
            .build((self, parameterDef) ->
                ClassTypeDef.of(StringBuilder.class).instantiate(ExpressionDef.constant(objectName + "[")).newLocal("strBuilder", variableDef -> {
                    ExpressionDef exp = variableDef;
                    for (int i = 0; i < properties.size(); i++) {
                        var beanProperty = properties.get(i);
                        ExpressionDef propertyValue;
                        // get property value
                        if ((!beanProperty.hasAnnotation(ToString.Exclude.class)) && beanProperty.getReadMethod().isPresent()) {
                            propertyValue = parameterDef.get(0).asVariable().invoke(
                                beanProperty.getReadMethod().get().getSimpleName(),
                                TypeDef.of(beanProperty.getType()),
                                List.of()
                            );
                        } else {
                            continue;
                        }

                        exp = exp.invoke("append", variableDef.type(),
                                ExpressionDef.constant(beanProperty.getName() + "="))
                            .invoke("append", variableDef.type(),
                                (TypeDef.of(beanProperty.getType()).isArray()) ?
                                    ClassTypeDef.of(Arrays.class).invokeStatic("toString",
                                        TypeDef.of(String.class), List.of(propertyValue)) : propertyValue)
                            .invoke("append", variableDef.type(),
                                ExpressionDef.constant((i == properties.size() - 1) ? "]" : ", "));
                    }

                    return exp.invoke("toString", TypeDef.of(String.class)).returning();
                })
            );
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
            .addParameter("instance", ClassTypeDef.of(objectName))
            .addParameter("o", TypeDef.of(Object.class))
            .build((self, parameterDef) -> {
                VariableDef instance = parameterDef.get(0).asVariable();
                VariableDef o = parameterDef.get(1).asVariable();
                return StatementDef.multi(
                    instance.asCondition(" == ", o)
                        .asConditionIf(ExpressionDef.trueValue().returning()),
                    o.isNull().asCondition(" || ",
                        instance.invoke("getClass", ClassTypeDef.of("Class"))
                            .asCondition(" != ", o.invoke("getClass", ClassTypeDef.of("Class"))))
                        .asConditionIf(ExpressionDef.falseValue().returning()),
                    o.cast(ClassTypeDef.of(objectName)).newLocal("other", variableDef -> {
                        ExpressionDef exp = null;
                        TypeDef propertyTypeDef;
                        ExpressionDef.CallInstanceMethod firstProperty;
                        ExpressionDef.CallInstanceMethod secondProperty;
                        for (PropertyElement beanProperty : properties) {
                            propertyTypeDef = TypeDef.of(beanProperty.getType());
                            if ( !beanProperty.hasAnnotation(EqualsAndHashCode.Exclude.class) && beanProperty.getReadMethod().isPresent()) {
                                firstProperty = instance.invoke(beanProperty.getReadMethod().get(), List.of());
                                secondProperty = variableDef.invoke(beanProperty.getReadMethod().get(), List.of());
                            } else {
                                continue;
                            }
                            ExpressionDef newEqualsExpression = propertyTypeDef.isPrimitive() ?
                                firstProperty.asCondition(" == ", secondProperty)
                                : firstProperty.asCondition(" == ", secondProperty)
                                .asConditionOr(firstProperty.isNonNull().asConditionAnd(
                                    (propertyTypeDef.isArray()) ?
                                        ClassTypeDef.of(Arrays.class).invokeStatic("equals", TypeDef.BOOLEAN, Arrays.asList(firstProperty, secondProperty))
                                        : firstProperty.invoke("equals", TypeDef.BOOLEAN, secondProperty)
                                ));
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
    private static void createHashCodeMethod(ClassDef.ClassDefBuilder classDefBuilder, String objectName, List<PropertyElement> properties) {
        MethodDef method = MethodDef.builder("hashCode")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("instance", ClassTypeDef.of(objectName))
            .returns(int.class)
            .build((self, parameterDef) -> StatementDef.multi(
                    parameterDef.get(0).asExpression().isNull().asConditionIf(ExpressionDef.constant(0).returning()),
                    TypeDef.of(int.class).initialize(ExpressionDef.constant(1)).newLocal("hashValue", hashValue -> {
                        List<StatementDef> hashUpdates = new ArrayList<>();
                        TypeDef propertyTypeDef;
                        ExpressionDef propertyGetter;
                        ExpressionDef propertyHashCalculation;

                        for (PropertyElement beanProperty : properties) {
                            propertyTypeDef = TypeDef.of(beanProperty.getType());
                            if (!beanProperty.hasAnnotation(EqualsAndHashCode.Exclude.class) && beanProperty.getReadMethod().isPresent()) {
                                propertyGetter = parameterDef.get(0).asVariable()
                                    .invoke(beanProperty.getReadMethod().get(), List.of());
                            } else {
                                continue;
                            }

                            propertyHashCalculation = getPropertyHashValue(propertyTypeDef, propertyGetter);
                            hashUpdates.add(hashValue.assign(
                                hashValue.asCondition(" * ", ExpressionDef.constant(HASH_MULTIPLIER)
                                    .asCondition(" + ", propertyHashCalculation.cast(TypeDef.of(int.class))))));
                        }
                        hashUpdates.add(hashValue.returning());
                        return StatementDef.multi(hashUpdates);
                    })
                )
            );
        classDefBuilder.addMethod(method);
    }

    /*

     */
    private static ExpressionDef getPropertyHashValue(TypeDef propertyTypeDef, ExpressionDef thisProperty) {
        ExpressionDef propertyHashCalculation;
        // calculate new property hash value
        if (propertyTypeDef.isArray()) {
            String methodName = (((TypeDef.Array) propertyTypeDef).dimensions() > 1) ?  "deepHashCode" : "hashCode";
            propertyHashCalculation = ClassTypeDef.of(Arrays.class).invokeStatic(methodName, TypeDef.of(int.class), thisProperty);
        } else if (propertyTypeDef.isPrimitive()) {
            String typeName = ((TypeDef.Primitive) propertyTypeDef).name();
            if (propertyTypeDef == TypeDef.BOOLEAN) {
                propertyHashCalculation = thisProperty.asConditionIfElse(
                    ExpressionDef.constant(TRUE_HASH_VALUE),
                    ExpressionDef.constant(FALSE_HASH_VALUE)
                );
            } else if (typeName.equals("float")) {
                propertyHashCalculation = ClassTypeDef.of(Float.class).invokeStatic("floatToIntBits", TypeDef.of(int.class), thisProperty);
            } else if (typeName.equals("double")) {
                // double -> long -> int
                propertyHashCalculation = ClassTypeDef.of(Double.class).invokeStatic("doubleToLongBits", TypeDef.of(int.class), thisProperty);
                propertyHashCalculation = propertyHashCalculation.asCondition(" >>> ",
                    ExpressionDef.constant(32).asCondition(" ^ ", propertyHashCalculation));
            } else if (typeName.equals("long")) {
                propertyHashCalculation = thisProperty.asCondition(" >>> ",
                    ExpressionDef.constant(32).asCondition(" ^ ", thisProperty));
            } else if (typeName.equals("char")) {
                propertyHashCalculation = thisProperty.asCondition(" - ", ExpressionDef.constant('0'));
            } else if (typeName.equals("short")) {
                propertyHashCalculation = thisProperty.asCondition(" & ", ExpressionDef.constant(0xffff));
            } else { // for int and byte, return itself as an int
                propertyHashCalculation = thisProperty;
            }
        } else { // OBJECT
            propertyHashCalculation = thisProperty.isNull().asConditionIfElse(
                ExpressionDef.constant(NULL_HASH_VALUE),
                thisProperty.invoke("hashCode", TypeDef.of(int.class), List.of())
            );
        }
        return propertyHashCalculation;
    }
}
