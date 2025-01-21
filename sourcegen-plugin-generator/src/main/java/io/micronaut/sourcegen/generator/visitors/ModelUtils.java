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
package io.micronaut.sourcegen.generator.visitors;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.EnumConstantElement;
import io.micronaut.inject.ast.EnumElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.visitors.JavadocUtils.TypeJavadoc;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassDef.ClassDefBuilder;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.EnumDef.EnumDefBuilder;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import io.micronaut.sourcegen.model.VariableDef.Local;

import javax.lang.model.element.Modifier;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A utility class for working with complex types, like enums and POJOs.
 */
@Internal
public class ModelUtils {

    /**
     * A utility method for getting a parameter type.
     * Complex types, like enums and POJOs get copied and re-mapped.
     *
     * @param context the Context
     * @param packageName The package name to use for new created models
     * @param element The element
     * @param objects A mutable list that can be extended with new objects
     * @return The type
     */
    public static TypeDef getType(
            VisitorContext context, String packageName, ClassElement element, List<ObjectDef> objects
    ) {
        if (element.isEnum()) {
            context.info("Copying plugin model for enum: " + element.getName());
            return copyEnum(context, packageName, element, objects);
        }
        if (isPOJO(element)) {
            context.info("Copying plugin model for POJO: " + element.getName());
            return copyPOJO(context, packageName, element, objects);
        }
        return TypeDef.of(element);
    }

    /**
     * Copy an existing enum to the plugin generated sources.
     *
     * @param context The visitor context
     * @param packageName The package name to copy to
     * @param element The element to copy
     * @param objects A mutable list of objects, where enum will be added to
     * @return The type of copied enum
     */
    private static ClassTypeDef copyEnum(
            VisitorContext context, String packageName, ClassElement element, List<ObjectDef> objects
    ) {
        EnumDefBuilder enumDefBuilder = EnumDef.builder(packageName + "."
                + element.getSimpleName().replaceAll(".+\\$", ""))
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc(JavadocUtils.getTaskJavadoc(context, element).javadoc().orElse(element.getName() + " enum."));
        if (element instanceof EnumElement enumElement) {
            for (EnumConstantElement constant: enumElement.elements()) {
                enumDefBuilder.addEnumConstant(constant.getName());
            }
        }
        EnumDef enumDef = enumDefBuilder.build();
        objects.add(enumDef);
        return enumDef.asTypeDef();
    }

    /**
     * Copy an existing POJO to the plugin generated sources.
     *
     * @param context The visitor context
     * @param packageName The package name to copy to
     * @param element The element to copy
     * @param objects A mutable list of objects, where enum will be added to
     * @return The type of copied POJO
     */
    private static TypeDef copyPOJO(
            VisitorContext context, String packageName, ClassElement element, List<ObjectDef> objects
    ) {
        TypeJavadoc javadoc = JavadocUtils.getTaskJavadoc(context, element);
        ClassDefBuilder classDefBuilder = ClassDef.builder(packageName + "."
                + element.getSimpleName().replaceAll(".+\\$", ""))
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc(javadoc.javadoc().orElse(element.getName() + " class."))
            .addSuperinterface(TypeDef.of(Serializable.class));
        List<PropertyDef> properties = new ArrayList<>();
        for (PropertyElement property: element.getBeanProperties()) {
            String propertyDoc = javadoc.elements().containsKey(property.getName()) ?
                javadoc.elements().get(property.getName()) :
                property.getName() + " property.";
            TypeDef type = getType(context, packageName, property.getType(), objects);
            PropertyDef propertyDef = PropertyDef.builder(property.getName())
                    .addModifiers(Modifier.PUBLIC)
                    .ofType(type)
                    .addJavadoc(propertyDoc)
                    .build();
            properties.add(propertyDef);
            classDefBuilder.addProperty(propertyDef);
        }
        for (int i = 0; i < properties.size(); i++) {
            classDefBuilder.addMethod(createWither(
                properties, javadoc.elements().get(properties.get(i).getName()), i
            ));
        }
        ClassDef classDef = classDefBuilder
            .addAllFieldsConstructor(Modifier.PUBLIC)
            .addConstructor(Collections.emptyList(), Modifier.PUBLIC)
            .build();
        objects.add(classDef);
        return classDef.asTypeDef();
    }

    /**
     * Converts a parameter value if required.
     * Conversion is required if the value is a model, so a new type was generated for it
     * instead of the original one.
     *
     * @param type The type
     * @param name The name to use for local variable
     * @param statements The modifiable statements to which a local variable may be added if needed
     * @param paramExpression The current expression for param
     * @return The new expression for param
     */
    public static ExpressionDef convertParameterIfRequired(
        ClassElement type, String name, List<StatementDef> statements, ExpressionDef paramExpression
    ) {
        if (type.isEnum()) {
            ClassTypeDef requiredType = ClassTypeDef.of(type);
            VariableDef.Local param = new VariableDef.Local(name, requiredType);
            statements.add(param.defineAndAssign(paramExpression.ifNull(ExpressionDef.constant(null), requiredType
                .invokeStatic("valueOf", requiredType, paramExpression.invoke("name", TypeDef.STRING)))));
            return param;
        }
        if (isPOJO(type)) {
            List<StatementDef> ifNonNullStatements = new ArrayList<>();
            Map<String, ExpressionDef> args = new HashMap<>();
            for (PropertyElement property: type.getBeanProperties()) {
                String capitalizedName = NameUtils.capitalize(property.getName());
                args.put(property.getName(), convertParameterIfRequired(
                    property.getType(),
                    name + capitalizedName,
                    ifNonNullStatements,
                    paramExpression.invoke("get" + capitalizedName, TypeDef.OBJECT)
                ));
            }

            Local pojo = new Local(name, TypeDef.of(type));
            Local temp = PluginUtils.instantiateType(type, name + "Temp", args, ifNonNullStatements);
            ifNonNullStatements.add(pojo.assign(temp));

            statements.add(pojo.defineAndAssign(ExpressionDef.constant(null)));
            statements.add(new StatementDef.If(
                paramExpression.isNonNull(), StatementDef.multi(ifNonNullStatements)
            ));
            return pojo;
        }
        return paramExpression;
    }

    /**
     * Create a wither method.
     *
     * @param properties The properties
     * @param javadoc The javadoc for property
     * @param index The property index
     * @return The wither method
     */
    private static MethodDef createWither(List<PropertyDef> properties, @Nullable String javadoc, int index) {
        PropertyDef property = properties.get(index);
        return MethodDef.builder("with" + NameUtils.capitalize(property.getName()))
            .addJavadoc("Create a copy and set " + property.getName() + "."
                + (javadoc != null ? "\n" + javadoc : "")
            )
            .addParameter(ParameterDef.of(property.getName(), property.getType()))
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.THIS)
            .build((t, params) -> {
                List<ExpressionDef> constructorValues = new ArrayList<>();
                for (int j = 0; j < properties.size(); j++) {
                    if (index == j) {
                        constructorValues.add(params.get(0));
                    } else {
                        constructorValues.add(t.field(
                            properties.get(j).getName(), properties.get(j).getType()
                        ));
                    }
                }
                return new StatementDef.Return(TypeDef.THIS.instantiate(constructorValues));
            });
    }

    /**
     * Whether it is considered a POJO.
     *
     * @param element The type
     * @return Whether it is POJO
     */
    public static boolean isPOJO(ClassElement element) {
        return !element.isEnum()
            && !element.isPrimitive()
            && !element.getPackageName().equals("java.util")
            && !element.getPackageName().equals("java.lang")
            && !element.getPackageName().equals("java.io");
    }

}
