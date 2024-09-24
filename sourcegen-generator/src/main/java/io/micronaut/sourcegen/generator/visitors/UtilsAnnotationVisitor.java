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
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.annotations.Equals;
import io.micronaut.sourcegen.annotations.HashCode;
import io.micronaut.sourcegen.annotations.ToString;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.*;

import java.util.*;
import javax.lang.model.element.Modifier;

import static io.micronaut.sourcegen.generator.visitors.BuilderAnnotationVisitor.*;

/**
 * The visitor that generates the util functions of a bean.
 *
 * @author Elif Kurtay
 * @since 1.3
 */

@Internal
public final class UtilsAnnotationVisitor implements TypeElementVisitor<Object, Object> {

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
    public void visitClass(ClassElement element, VisitorContext context) {
        if (!(element.hasStereotype(ToString.class) || element.hasStereotype(Equals.class) || element.hasStereotype(HashCode.class))) {
            return;
        }

        if (processed.contains(element.getName())) {
            return;
        }
        try {
            String simpleName = element.getSimpleName() + "Utils";
            String utilsClassName = element.getPackageName() + "." + simpleName;

            // class def and annotations
            ClassDef.ClassDefBuilder utilsBuilder = ClassDef.builder(utilsClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

            // TODO: should I check for properties that have getters?
            List<PropertyElement> properties = element.getBeanProperties();

            // create the utils functions
            if (element.hasStereotype(ToString.class)) {
                createToStringMethod(utilsBuilder, element.getSimpleName(), properties);
            }
            if (element.hasStereotype(Equals.class)) {
                createEqualsMethod(utilsBuilder, element.getSimpleName(), properties);
            }
            if (element.hasStereotype(HashCode.class)) {
                createHashCodeMethod(utilsBuilder, element.getSimpleName(), properties);
            }

            SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
            if (sourceGenerator == null) {
                return;
            }

            ClassDef utilsDef = utilsBuilder.build();
            processed.add(element.getName());
            context.visitGeneratedSourceFile(
                utilsDef.getPackageName(),
                utilsDef.getSimpleName(),
                element
            ).ifPresent(sourceFile -> {
                try {
                    sourceFile.write(
                        writer -> sourceGenerator.write(utilsDef, writer)
                    );
                } catch (Exception e) {
                    throw new ProcessingException(element, "Failed to generate a utilsBuilder: " + e.getMessage(), e);
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

    /* toString method
     */
    private static void createToStringMethod(ClassDef.ClassDefBuilder classDefBuilder, String objectName, List<PropertyElement> properties) {
        List<StatementDef> statements = new ArrayList<>();
        VariableDef.Local strBuilder = new VariableDef.Local("strBuilder", ClassTypeDef.of(StringBuilder.class));
        VariableDef arrays = new VariableDef.Local("java.util.Arrays", ClassTypeDef.of(java.util.Arrays.class));
        VariableDef thisVariable = new VariableDef.MethodParameter("object", ClassTypeDef.of(objectName));

        statements.add(strBuilder.defineAndAssign(ClassTypeDef.of(StringBuilder.class).instantiate()));
        statements.add(strBuilder.invoke(
            "append",
            ClassTypeDef.of(strBuilder.getClass()),
            ExpressionDef.constant(objectName + "[")
        ));
        for (int i = 0; i < properties.size(); i++) {
            PropertyElement beanProperty = properties.get(i);
            TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
            ExpressionDef.CallInstanceMethod thisProperty = thisVariable
                .invoke(
                    "get" + StringUtils.capitalize(beanProperty.getSimpleName()),
                    propertyTypeDef,
                    List.of()
                );
            statements.add(strBuilder.invoke(
                "append",
                ClassTypeDef.of(strBuilder.getClass()),
                ExpressionDef.constant(beanProperty.getSimpleName() + "=")
            ).invoke(
                "append",
                ClassTypeDef.of(strBuilder.getClass()),
                (TypeDef.of(beanProperty.getType()) instanceof TypeDef.Array) ?
                    arrays.invoke(
                        "toString",
                        TypeDef.of(String.class),
                        thisProperty)
                    :
                    thisProperty
            ).invoke(
                "append",
                ClassTypeDef.of(strBuilder.getClass()),
                ExpressionDef.constant((i == properties.size() - 1) ? "]" : ", ")
            ));
        }
        statements.add(new StatementDef.Return(strBuilder.invoke("toString", TypeDef.of(String.class))));

        MethodDef method = MethodDef.builder("toString")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(String.class)
            .addParameter("object", ClassTypeDef.of(objectName))
            .addStatements(statements)
            .build();
        classDefBuilder.addMethod(method);
    }

    /* complete equals method
     */
    private static void createEqualsMethod(ClassDef.ClassDefBuilder classDefBuilder, String objectName, List<PropertyElement> properties) {
        MethodDef method = MethodDef.builder("equals")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter("firstObject", TypeDef.of(Object.class))
            .addParameter("secondObject", TypeDef.of(Object.class))
            .build((self, parameterDef) -> {
                // local variables needed
                VariableDef classname = new VariableDef.Local(objectName, ClassTypeDef.of(objectName));
                VariableDef arrays = new VariableDef.Local("java.util.Arrays", ClassTypeDef.of(java.util.Arrays.class));
                List<StatementDef> statements = new ArrayList<>();
                VariableDef.Local firstObject = new VariableDef.Local("first", classname.type());
                VariableDef.Local secondObject = new VariableDef.Local("second", classname.type());
                VariableDef.Local bothNullCondition = new VariableDef.Local("bothNullCondition", TypeDef.of(boolean.class));
                VariableDef.Local equalsCondition = new VariableDef.Local("equalsCondition", TypeDef.of(boolean.class));
                VariableDef.Local isPropertyEqual = new VariableDef.Local("isPropertyEqual", TypeDef.of(boolean.class));
                VariableDef.Local isCorrectInstance = new VariableDef.Local("isCorrectInstance", TypeDef.of(boolean.class));

                // early exist scenarios: object references match, objects are not the correct instance
                // if not returned, cast objects to the correct class and define local parameters
                statements.add(StatementDef.multi(
                    parameterDef.get(0).asVariable().asCondition(" == ", parameterDef.get(1).asVariable())
                        .asConditionIf(ExpressionDef.constant(true).returning()),
                    new StatementDef.DefineAndAssign(
                        isCorrectInstance,
                        new ExpressionDef.Condition(" && ",
                            parameterDef.get(0).asVariable().asCondition(" instanceof ", classname),
                            parameterDef.get(1).asVariable().asCondition(" instanceof ", classname))
                    ),
                    new StatementDef.If(
                        new ExpressionDef.Condition(" == ", isCorrectInstance, ExpressionDef.constant(false)),
                        ExpressionDef.constant(false).returning()
                    ),
                    new StatementDef.DefineAndAssign(firstObject, new ExpressionDef.Cast(classname.type(), parameterDef.get(0).asVariable())),
                    new StatementDef.DefineAndAssign(secondObject, new ExpressionDef.Cast(classname.type(), parameterDef.get(1).asVariable())),
                    new StatementDef.DefineAndAssign(bothNullCondition, ExpressionDef.constant(false)),
                    new StatementDef.DefineAndAssign(equalsCondition, ExpressionDef.constant(false)),
                    new StatementDef.DefineAndAssign(isPropertyEqual, ExpressionDef.constant(false))
                ));

                // property equal checks
                for (PropertyElement beanProperty : properties) {
                    TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
                    ExpressionDef.CallInstanceMethod firstProperty = firstObject
                        .invoke(
                            "get" + StringUtils.capitalize(beanProperty.getSimpleName()),
                            propertyTypeDef,
                            List.of()
                        );
                    ExpressionDef.CallInstanceMethod secondProperty = secondObject
                        .invoke(
                            "get" + StringUtils.capitalize(beanProperty.getSimpleName()),
                            propertyTypeDef,
                            List.of()
                        );

                    if (propertyTypeDef instanceof TypeDef.Primitive) {
                        // ==, primitives do not need null check
                        statements.add(new StatementDef.If(
                            new ExpressionDef.Condition(" != ", firstProperty, secondProperty),
                            ExpressionDef.constant(false).returning()
                        ));
                    } else {
                        // .equals, check for double null or equal objects
                        statements.add(new StatementDef.Assign(bothNullCondition,
                                new ExpressionDef.Condition(" && ",
                                    firstProperty.isNull(),
                                    secondProperty.isNull())
                            )
                        );
                        if (propertyTypeDef instanceof TypeDef.Array) {
                            statements.add(new StatementDef.Assign(equalsCondition,
                                    new ExpressionDef.Condition(" && ",
                                        firstProperty.isNonNull(),
                                        arrays.invoke("equals", TypeDef.BOOLEAN, Arrays.asList(firstProperty, secondProperty))
                                    )
                                )
                            );
                        } else {
                            statements.add(new StatementDef.Assign(equalsCondition,
                                    new ExpressionDef.Condition(" && ",
                                        firstProperty.isNonNull(),
                                        firstProperty.invoke("equals", TypeDef.BOOLEAN, secondProperty)
                                    )
                                )
                            );
                        }
                        statements.add(new StatementDef.Assign(isPropertyEqual,
                                new ExpressionDef.Condition(" || ", bothNullCondition, equalsCondition)
                            )
                        );
                        statements.add(
                            new StatementDef.If(
                                new ExpressionDef.Condition(" == ", isPropertyEqual, ExpressionDef.constant(false)),
                                ExpressionDef.constant(false).returning()
                            )
                        );
                    }
                }
                statements.add(new StatementDef.Return(ExpressionDef.constant(true)));
                return StatementDef.multi(statements);
            });
        classDefBuilder.addMethod(method);
    }

    /* complete hashCode method
     */
    private static void createHashCodeMethod(ClassDef.ClassDefBuilder classDefBuilder, String objectName, List<PropertyElement> properties) {
        MethodDef method = MethodDef.builder("hashCode")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("object", ClassTypeDef.of(objectName))
            .returns(int.class)
            .build((self, parameterDef) -> {
                List<StatementDef> statements = new ArrayList<>();
                ExpressionDef propertyHashExpression;
                VariableDef.Local hashValue = new VariableDef.Local("hashValue", TypeDef.of(int.class));
                VariableDef arrays = new VariableDef.Local("java.util.Arrays", ClassTypeDef.of(java.util.Arrays.class));

                // initialise local properties
                statements.add(new StatementDef.DefineAndAssign(hashValue, ExpressionDef.constant(1)));

                // add all property hash calculations
                for (PropertyElement beanProperty : properties) {
                    TypeDef propertyTypeDef = TypeDef.of(beanProperty.getType());
                    ExpressionDef.CallInstanceMethod thisProperty = parameterDef.get(0).asVariable()
                        .invoke(
                            "get" + StringUtils.capitalize(beanProperty.getSimpleName()),
                            propertyTypeDef,
                            List.of()
                        );

                    // calculate new property hash value
                    if (propertyTypeDef instanceof TypeDef.Array) {
                        String methodName = (((TypeDef.Array) propertyTypeDef).dimensions() > 1) ?  "deepHashCode" : "hashCode";
                        propertyHashExpression = arrays.invoke(methodName, TypeDef.of(int.class), thisProperty);
                    } else if (propertyTypeDef instanceof TypeDef.Primitive) {
                        String typeName = ((TypeDef.Primitive) propertyTypeDef).name();
                        if (propertyTypeDef == TypeDef.BOOLEAN) {
                            propertyHashExpression = new ExpressionDef.IfElse(
                                thisProperty,
                                ExpressionDef.constant(TRUE_HASH_VALUE),
                                ExpressionDef.constant(FALSE_HASH_VALUE)
                            );
                        } else if (typeName.equals("float")) {
                            VariableDef floatClass = new VariableDef.Local("Float", ClassTypeDef.of(float.class));
                            propertyHashExpression = floatClass.invoke("floatToIntBits", TypeDef.of(int.class), thisProperty);
                        } else if (typeName.equals("double")) {
                            // double -> long -> int
                            VariableDef mathClass = new VariableDef.Local("Double", ClassTypeDef.of(Double.class));
                            propertyHashExpression = mathClass.invoke("doubleToLongBits", TypeDef.of(int.class), thisProperty);
                            propertyHashExpression = new ExpressionDef.Condition(
                                " >>> ",
                                propertyHashExpression,
                                new ExpressionDef.Condition(" ^ ", ExpressionDef.constant(32), propertyHashExpression));
                        } else if (typeName.equals("long")) {
                            propertyHashExpression = new ExpressionDef.Condition(
                                " >>> ",
                                thisProperty,
                                new ExpressionDef.Condition(" ^ ", ExpressionDef.constant(32), thisProperty));
                        } else if (typeName.equals("char")) {
                            propertyHashExpression = new ExpressionDef.Condition(" - ", thisProperty, ExpressionDef.constant('0'));
                        } else if (typeName.equals("short")) {
                            propertyHashExpression = new ExpressionDef.Condition(" & ", thisProperty, ExpressionDef.constant(0xffff));
                        } else { // for int and byte, return itself as an int
                            propertyHashExpression = thisProperty;
                        }
                    } else { // OBJECT
                        propertyHashExpression = new ExpressionDef.IfElse(
                            thisProperty.isNull(),
                            ExpressionDef.constant(NULL_HASH_VALUE),
                            thisProperty.invoke("hashCode", TypeDef.of(int.class), List.of())
                            );
                    }

                    // hash update
                    statements.add(
                        new StatementDef.Assign(hashValue,
                            new ExpressionDef.Condition(" * ", hashValue,
                            new ExpressionDef.Condition(" + ",
                                ExpressionDef.constant(HASH_MULTIPLIER),
                                new ExpressionDef.Cast(TypeDef.of(int.class), propertyHashExpression))))
                    );
                }
                statements.add(hashValue.returning());
                return StatementDef.multi(statements);
            });
        classDefBuilder.addMethod(method);
    }

}