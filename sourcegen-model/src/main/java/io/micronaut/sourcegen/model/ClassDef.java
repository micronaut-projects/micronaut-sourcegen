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
import org.jspecify.annotations.Nullable;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * The class definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class ClassDef extends ObjectDef {

    private final List<FieldDef> fields;
    private final List<TypeDef.TypeVariable> typeVariables;
    @Nullable
    private final ClassTypeDef superclass;
    @Nullable
    private final StatementDef staticInitializer;

    @SuppressWarnings("ParameterNumber")
    private ClassDef(ClassTypeDef.ClassName className,
                     EnumSet<Modifier> modifiers,
                     List<FieldDef> fields,
                     List<MethodDef> methods,
                     List<PropertyDef> properties,
                     List<AnnotationDef> annotations,
                     List<String> javadoc,
                     List<TypeDef.TypeVariable> typeVariables,
                     List<TypeDef> superinterfaces,
                     @Nullable
                     ClassTypeDef superclass,
                     List<ObjectDef> innerTypes,
                     @Nullable
                     StatementDef staticInitializer,
                     boolean synthetic) {
        super(className, modifiers, annotations, javadoc, methods, properties, superinterfaces, innerTypes, synthetic);
        ClassTypeDef.of(this);
        this.fields = fields;
        this.typeVariables = typeVariables;
        this.superclass = superclass;
        this.staticInitializer = staticInitializer;
    }

    @Override
    public ClassDef withClassName(ClassTypeDef.ClassName className) {
        return new ClassDef(className, modifiers, fields, methods, properties, annotations, javadoc, typeVariables, superinterfaces, superclass, innerTypes, staticInitializer, synthetic);
    }

    @Override
    public ClassTypeDef asTypeDef() {
        if (typeVariables.isEmpty()) {
            return super.asTypeDef();
        }
        return TypeDef.parameterized(super.asTypeDef(), typeVariables.toArray(new TypeDef.TypeVariable[0]));
    }

    public static ClassDefBuilder builder(String name) {
        return new ClassDefBuilder(name);
    }

    public List<FieldDef> getFields() {
        return fields;
    }

    public List<TypeDef.TypeVariable> getTypeVariables() {
        return typeVariables;
    }

    @Nullable
    public ClassTypeDef getSuperclass() {
        return superclass;
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

    public FieldDef getField(String name) {
        FieldDef field = findField(name);
        if (field == null) {
            throw new IllegalStateException("Class: " + this.className + " doesn't have a field: " + name);
        }
        return field;
    }

    public boolean hasField(String name) {
        FieldDef field = findField(name);
        if (field != null) {
            return true;
        }
        if (superclass != null) {
            if (superclass instanceof ClassTypeDef.ClassElementType classElementType) {
                return classElementType.classElement().findField(name).isPresent();
            }
            if (superclass instanceof ClassTypeDef.ClassDefType classDefType && classDefType.objectDef() instanceof ClassDef classDef) {
                return classDef.hasField(name);
            }
            if (superclass instanceof ClassTypeDef.ClassDefType classDefType && classDefType.objectDef() instanceof EnumDef enumDef) {
                return enumDef.hasField(name);
            }
            if (superclass instanceof ClassTypeDef.JavaClass javaClass) {
                try {
                    javaClass.type().getField(name);
                    return true;
                } catch (NoSuchFieldException e) {
                    return false;
                }
            }
            // We cannot properly validate if the field exists in the super class
            return true;
        }
        return false;
    }

    @Nullable
    public StatementDef getStaticInitializer() {
        return staticInitializer;
    }

    @Override
    public String toString() {
        return "ClassDef{" + "name='" + className + '\'' + '}';
    }

    /**
     * The class definition builder.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class ClassDefBuilder extends ObjectDefBuilder<ClassDefBuilder> {

        private final List<FieldDef> fields = new ArrayList<>();
        private final List<TypeDef.TypeVariable> typeVariables = new ArrayList<>();
        @Nullable
        private ClassTypeDef superclass;
        @Nullable
        private StatementDef staticInitializer;

        private ClassDefBuilder(String name) {
            super(name);
        }

        public ClassDefBuilder superclass(ClassTypeDef superclass) {
            this.superclass = superclass;
            return this;
        }

        public ClassDefBuilder superclass(Class<?> superclass) {
            this.superclass = ClassTypeDef.of(superclass);
            return this;
        }

        public ClassDefBuilder addField(FieldDef field) {
            fields.add(field);
            return this;
        }

        /**
         * Adds fields.
         * @param fields The fields
         * @return the builder
         * @since 1.5
         */
        public ClassDefBuilder addFields(Collection<FieldDef> fields) {
            fields.forEach(this::addField);
            return this;
        }

        public ClassDefBuilder addTypeVariable(TypeDef.TypeVariable typeVariable) {
            typeVariables.add(typeVariable);
            return this;
        }

        public ClassDefBuilder addStaticInitializer(StatementDef staticInitializer) {
            this.staticInitializer = staticInitializer;
            return this;
        }

        public ClassDef build() {
            return new ClassDef(new ClassTypeDef.ClassName(name), modifiers, fields, methods, properties, annotations, javadoc, typeVariables, superinterfaces, superclass, innerTypes, staticInitializer, synthetic);
        }

        /**
         * Add a constructor.
         *
         * @param parameterDefs The fields to set in the constructor
         * @param modifiers The method modifiers
         * @return this
         */
        public ClassDefBuilder addConstructor(Collection<ParameterDef> parameterDefs, Modifier... modifiers) {
            return this.addMethod(
                MethodDef.constructor(parameterDefs, modifiers)
            );
        }

        /**
         * Add a constructor for all fields.
         *
         * @param modifiers The modifiers
         * @return this
         */
        public ClassDefBuilder addAllFieldsConstructor(Modifier... modifiers) {
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
        public ClassDefBuilder addNoFieldsConstructor(Modifier... modifiers) {
            return this.addMethod(
                MethodDef.constructor(Collections.emptyList(), modifiers)
            );
        }

    }

}
