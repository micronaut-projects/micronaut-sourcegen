/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.core.annotation.Internal;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Model-level enum member synthesis shared by bytecode backends.
 *
 * @since 2.2
 */
@Internal
public final class EnumGenUtils {

    private static final Method CLONE_METHOD = requiredMethod(Object.class, "clone");
    private static final Method ENUM_VALUE_OF_METHOD = requiredMethod(Enum.class, "valueOf", Class.class, String.class);
    private static final Constructor<?> ENUM_CONSTRUCTOR = requiredConstructor(Enum.class, String.class, int.class);

    private EnumGenUtils() {
    }

    /**
     * Generates a class definition from an enum definition.
     *
     * @param enumDef The enum definition
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
                    aThis.superRef().invokeSuperConstructor(ENUM_CONSTRUCTOR, methodParameters.get(0), methodParameters.get(1))));
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
                aThis.superRef().invokeSuperConstructor(ENUM_CONSTRUCTOR, methodParameters.get(0), methodParameters.get(1)),
                aThis.invoke(constructorMethod, methodParameters.subList(2, methodParameters.size()))
            )));
    }

    /**
     * Determines whether a field is one of the synthesized enum constants.
     *
     * @param objectDef The object definition
     * @param fieldDef The field
     * @return {@code true} if the field is an enum constant
     */
    public static boolean isEnumField(ObjectDef objectDef, FieldDef fieldDef) {
        Optional<ExpressionDef> initializer = fieldDef.getInitializer();
        return objectDef instanceof ClassDef classDef && isEnum(classDef)
            && initializer.isPresent()
            && initializer.get() instanceof ExpressionDef.NewInstance ni
            && ni.type().getName().equals(classDef.getName());
    }

    /**
     * Determines whether a class definition represents the lowered form of an enum.
     *
     * @param classDef The class definition
     * @return {@code true} if the class is an enum
     */
    public static boolean isEnum(ClassDef classDef) {
        return classDef.getSuperclass() != null && classDef.getSuperclass().getName().equals(Enum.class.getName());
    }

    private static Method requiredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Constructor<?> requiredConstructor(Class<?> type, Class<?>... parameterTypes) {
        try {
            return type.getDeclaredConstructor(parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
