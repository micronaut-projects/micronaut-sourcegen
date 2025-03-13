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
package io.micronaut.sourcegen.model;

import io.micronaut.core.annotation.Experimental;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.lang.String.join;

/**
 * The enum definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class EnumDef extends ObjectDef {

    private final List<FieldDef> fields;
    private final List<EnumConstantDef> enumConstants;

    private EnumDef(ClassTypeDef.ClassName className,
                    EnumSet<Modifier> modifiers,
                    List<FieldDef> fields,
                    List<MethodDef> methods,
                    List<PropertyDef> properties,
                    List<AnnotationDef> annotations,
                    List<String> javadoc,
                    List<EnumConstantDef> enumConstants,
                    List<TypeDef> superinterfaces,
                    List<ObjectDef> innerTypes,
                    boolean synthetic) {
        super(className, modifiers, annotations, javadoc, methods, properties, superinterfaces, innerTypes, synthetic);
        this.fields = fields;
        this.enumConstants = enumConstants;
    }

    @Override
    public EnumDef withClassName(ClassTypeDef.ClassName className) {
        return new EnumDef(className, modifiers, fields, methods, properties, annotations, javadoc, enumConstants, superinterfaces, innerTypes, synthetic);
    }

    public static EnumDefBuilder builder(String name) {
        return new EnumDefBuilder(name);
    }

    public List<FieldDef> getFields() {
        return fields;
    }

    public List<EnumConstantDef> getEnumConstants() {
        return enumConstants;
    }

    @Nullable
    public FieldDef findField(String name) {
        for (FieldDef field : fields) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        for (PropertyDef property : getProperties()) {
            if (property.getName().equals(name)) {
                return FieldDef.builder(property.getName()).ofType(property.getType()).build();
            }
        }
        return null;
    }

    @NonNull
    public FieldDef getField(String name) {
        for (EnumConstantDef constant: enumConstants) {
            if (constant.name().equals(name)) {
                return FieldDef.builder(name, asTypeDef()).build();
            }
        }
        FieldDef field = findField(name);
        if (field == null) {
            throw new IllegalStateException("Enum: " + this.className + " doesn't have a field: " + name);
        }
        return null;
    }

    public boolean hasField(String name) {
        FieldDef property = findField(name);
        return property != null;
    }

    /**
     * A type defining an enum constant.
     * @since 1.7
     * @param name The enum constant name
     * @param constructorArgs The arguments passed to the enum constructor
     * @param javadoc The documentation
     */
    @Experimental
    public record EnumConstantDef(
        String name,
        List<ExpressionDef> constructorArgs,
        List<String> javadoc
    ) {
        public static EnumConstantDefBuilder builder(String name) {
            return new EnumConstantDefBuilder(name);
        }
    }

    /**
     * Builder for {@link EnumConstantDef}.
     * @since 1.7
     */
    @Experimental
    public static final class EnumConstantDefBuilder {
        private final String name;
        private List<ExpressionDef> constructorArgs;
        private List<String> javadoc = new ArrayList<>();

        public EnumConstantDefBuilder(String name) {
            this.name = name;
        }

        public EnumConstantDefBuilder withConstructorArgs(List<ExpressionDef> constructorArgs) {
            this.constructorArgs = constructorArgs;
            return this;
        }

        public EnumConstantDefBuilder addJavadoc(String javadoc) {
            this.javadoc.add(javadoc);
            return this;
        }

        public EnumConstantDef build() {
            return new EnumConstantDef(name, constructorArgs, javadoc);
        }

    }

    /**
     * The enum definition builder.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class EnumDefBuilder extends ObjectDefBuilder<EnumDefBuilder> {

        private final List<FieldDef> fields = new ArrayList<>();
        private final List<EnumConstantDef> enumConstants = new ArrayList<>();

        private EnumDefBuilder(String name) {
            super(name);
        }

        public EnumDefBuilder addField(FieldDef field) {
            fields.add(field);
            return this;
        }

        public EnumDefBuilder addEnumConstant(EnumConstantDef constant) {
            enumConstants.add(constant);
            return this;
        }

        public EnumDefBuilder addEnumConstant(String name) {
            String constName = getConstantName(name);
            enumConstants.add(new EnumConstantDef(constName, Collections.emptyList(), null));
            return this;
        }

        public EnumDefBuilder addEnumConstant(String name, ExpressionDef... values) {
            Objects.requireNonNull(values, "Values cannot be null");
            String constName = getConstantName(name);
            enumConstants.add(new EnumConstantDef(constName, List.of(values), null));
            return this;
        }

        public EnumDef build() {
            if (!enumConstants.isEmpty()) {
                Set<Integer> valueCount = new HashSet<>();
                for (EnumConstantDef constantDef : enumConstants) {
                    if (constantDef.constructorArgs == null || constantDef.constructorArgs.isEmpty()) {
                        continue;
                    }

                    int constCount = constantDef.constructorArgs.size();
                    if (valueCount.contains(constCount)) {
                        continue;
                    } else {
                        valueCount.add(constCount);
                    }

                    boolean hasConstructor = false;
                    for (MethodDef methodDef: methods) {
                        if (methodDef.isConstructor() && methodDef.getParameters().size() == constCount) {
                            hasConstructor = true;
                        }
                        if (methodDef.isConstructor() && !methodDef.getModifiers().contains(Modifier.PRIVATE)) {
                            throw new IllegalStateException("The constructor of enum: " + name + " has to be private.");
                        }
                    }
                    if (!hasConstructor) {
                        throw new IllegalStateException("Enum: " + name + " doesn't have a matching constructor for constant " + constantDef.name);
                    }
                }
            }
            return new EnumDef(new ClassTypeDef.ClassName(name), modifiers, fields, methods, properties, annotations, javadoc, enumConstants, superinterfaces, innerTypes, synthetic);
        }

        /**
         * Add a constructor.
         *
         * @param parameterDefs The fields to set in the constructor
         * @param modifiers The method modifiers
         * @return this
         */
        public EnumDefBuilder addConstructor(Collection<ParameterDef> parameterDefs, Modifier... modifiers) {
            return this.addMethod(
                MethodDef.constructor(parameterDefs, modifiers)
            );
        }

        /**
         * Add a constructor for all fields and property.
         *
         * @param modifiers The modifiers
         * @return this
         */
        public EnumDefBuilder addAllFieldsConstructor(Modifier... modifiers) {
            List<ParameterDef> constructorParameters = new ArrayList<>();
            for (PropertyDef property : properties) {
                constructorParameters.add(ParameterDef.of(property.getName(), property.getType()));
            }
            for (FieldDef field: fields) {
                constructorParameters.add(ParameterDef.of(field.getName(), field.getType()));
            }
            return this.addMethod(
                MethodDef.constructor(constructorParameters, modifiers)
            );
        }

        /**
         * Add a constructor with no arguments.
         *
         * @param modifiers The method modifiers
         * @return this
         */
        public EnumDefBuilder addNoFieldsConstructor(Modifier... modifiers) {
            return this.addMethod(
                MethodDef.constructor(Collections.emptyList(), modifiers)
            );
        }

        private static String getConstantName(String input) {
            if (input.equals(input.toUpperCase())) {
                return input;
            }
            String cleanedInput = input.replaceAll("[-_]", " ")
                .replaceAll("(?<!^)(?=[A-Z])", " ")
                .replaceAll("[^a-zA-Z0-9 ]", "")
                .trim();

            while (!Character.isJavaIdentifierStart(cleanedInput.charAt(0))) {
                cleanedInput = cleanedInput.substring(1);
            }

            // Split into words
            String[] words = cleanedInput.split("\\s+");
            try {
                // Check if the input is acceptable
                if (words.length == 0 || words[0].isEmpty()) {
                    throw new IllegalArgumentException("The enum constant name is not an acceptable identifier name.");
                }
                for (int i = 0; i < words.length; i++) {
                    words[i] = words[i].toUpperCase();
                }
                String constantName = join("_", words);

                if (!constantName.equals(input)) {
                    throw new IllegalArgumentException("The enum constant name does not follow the conventions for constants, it should be changed accordingly.");
                }
                return constantName;
            } catch (IllegalArgumentException e) {
                throw e;
            }
        }

    }

}
