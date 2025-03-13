/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.EnumDef.EnumConstantDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The enum generator utils.
 *
 * @author Denis Stepanov
 * @since 1.5
 */
@Internal
public class EnumGenUtils {

    private static final java.lang.reflect.Method CLONE_METHOD = ReflectionUtils.getRequiredMethod(Object.class, "clone");
    private static final java.lang.reflect.Method ENUM_VALUE_OF_METHOD = ReflectionUtils.getRequiredMethod(Enum.class, "valueOf", Class.class, String.class);
    private static final java.lang.reflect.Constructor<?> ENUM_CONSTRUCTOR = ReflectionUtils.getRequiredInternalConstructor(Enum.class, String.class, int.class);

    /**
     * Generate the {@link ClassDef} from {@link EnumDef}.
     *
     * @param enumDef The enum def
     * @return The class definition
     */
    public static ClassDef toClassDef(EnumDef enumDef) {
        ClassTypeDef enumTypeDef = ClassTypeDef.of(enumDef.getName());

        ClassTypeDef baseEnumTypeDef = ClassTypeDef.of(Enum.class);

        ClassDef.ClassDefBuilder classDefBuilder = ClassDef.builder(enumDef.getName())
            .addFields(enumDef.getFields())
            .addModifiers(enumDef.getModifiers())
            .addModifiers(Modifier.FINAL)
            .superclass(TypeDef.parameterized(baseEnumTypeDef, enumTypeDef))
            .addSuperinterfaces(enumDef.getSuperinterfaces())
            .addInnerType(enumDef.getInnerTypes());

        if (enumDef.isSynthetic()) {
            classDefBuilder.synthetic();
        }

        int i = 0;
        for (EnumConstantDef e : enumDef.getEnumConstants()) {

            List<ExpressionDef> values = new ArrayList<>();
            values.add(ExpressionDef.constant(e.name()));
            values.add(TypeDef.Primitive.INT.constant(i++));
            values.addAll(e.constructorArgs());

            FieldDef enumField = FieldDef.builder(e.name(), enumTypeDef)
                .addModifiers(Modifier.FINAL, Modifier.STATIC, Modifier.PUBLIC)
                .initializer(enumTypeDef.instantiate(values))
                .build();

            classDefBuilder.addField(enumField);
        }

        int constructorIndex = 0;
        boolean constructorAdded = false;
        for (MethodDef method : enumDef.getMethods()) {
            if (!method.isConstructor()) {
                continue;
            }
            addEnumConstructor(classDefBuilder, method, constructorIndex);
            constructorAdded = true;
        }
        if (!constructorAdded) {
            classDefBuilder.addMethod(MethodDef.override(ENUM_CONSTRUCTOR)
                .overrideModifiers(Modifier.PRIVATE)
                .build((aThis, methodParameters) ->
                    aThis.superRef().invokeConstructor(ENUM_CONSTRUCTOR, methodParameters.get(0), methodParameters.get(1))));
        }

        MethodDef internalValuesMethod = MethodDef.builder("$values")
            .addModifiers(Modifier.STATIC, Modifier.PRIVATE)
            .build((aThis, methodParameters) ->
                enumTypeDef.array()
                    .instantiate(
                        enumDef.getEnumConstants()
                            .stream()
                            .<ExpressionDef>map(e -> enumTypeDef.getStaticField(e.name(), enumTypeDef))
                            .toList()
                    )
                    .returning());

        classDefBuilder.addMethod(internalValuesMethod);

        FieldDef valuesField = FieldDef.builder("$VALUES").ofType(enumTypeDef.array())
            .addModifiers(Modifier.STATIC, Modifier.PRIVATE)
            .initializer(enumTypeDef.invokeStatic(internalValuesMethod))
            .build();

        classDefBuilder.addField(valuesField);

        classDefBuilder.addMethod(MethodDef.builder("values")
            .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
            .returns(enumTypeDef.array())
            .build((aThis2, methodParameters2) ->
                enumTypeDef.getStaticField(valuesField)
                    .invoke(CLONE_METHOD)
                    .cast(enumTypeDef.array())
                    .returning()));

        classDefBuilder.addMethod(MethodDef.builder("valueOf")
            .addParameter(ParameterDef.of("value", TypeDef.STRING))
            .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
            .build((aThis, methodParameters) ->
                baseEnumTypeDef
                    .invokeStatic(ENUM_VALUE_OF_METHOD, ExpressionDef.constant(enumTypeDef), methodParameters.get(0))
                    .cast(enumTypeDef)
                    .returning()));

        enumDef.getMethods().stream().filter(m -> !m.isConstructor()).forEach(classDefBuilder::addMethod);

        return classDefBuilder.build();
    }

    private static void addEnumConstructor(ClassDef.ClassDefBuilder classDefBuilder, MethodDef method, int constructorIndex) {
        // To avoid modifying the created constructor, we will create a method and invoke it from the modified enum constructor
        MethodDef constructorMethod = MethodDef.builder("$constructor" + constructorIndex)
            .addModifiers(Modifier.PRIVATE)
            .addParameters(method.getParameters())
            .returns(TypeDef.VOID)
            .addStatements(method.getStatements())
            .build();
        classDefBuilder.addMethod(constructorMethod);

        classDefBuilder.addMethod(MethodDef.constructor()
            .addModifiers(Modifier.PRIVATE)
            .addParameters(ENUM_CONSTRUCTOR.getParameterTypes())
            .addParameters(method.getParameters())
            .build((aThis, methodParameters) -> StatementDef.multi(
                aThis.superRef().invokeConstructor(ENUM_CONSTRUCTOR, methodParameters.get(0), methodParameters.get(1)),
                aThis.invoke(constructorMethod, methodParameters.subList(2, methodParameters.size()))
            )));
    }

    /**
     * Is enum field.
     *
     * @param objectDef The object def
     * @param fieldDef  The field
     * @return true if is an enum field
     */
    public static boolean isEnumField(ObjectDef objectDef, FieldDef fieldDef) {
        Optional<ExpressionDef> initializer = fieldDef.getInitializer();
        return objectDef instanceof ClassDef classDef && isEnum(classDef)
            && initializer.isPresent()
            && initializer.get() instanceof ExpressionDef.NewInstance ni
            && ni.type().getName().equals(classDef.getName());
    }

    /**
     * Is enum class.
     *
     * @param classDef The class def
     * @return true if the enum class
     */
    public static boolean isEnum(ClassDef classDef) {
        return classDef.getSuperclass() != null && classDef.getSuperclass().getName().equals(Enum.class.getName());
    }

}
