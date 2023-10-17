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
import java.util.EnumSet;
import java.util.List;

/**
 * The class definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class ClassDef extends AbstractElement implements ObjectDef {

    private final List<FieldDef> fields;
    private final List<MethodDef> methods;
    private final List<PropertyDef> properties;
    private final List<TypeDef.TypeVariable> typeVariables;
    private final List<TypeDef> superinterfaces;

    private ClassDef(String name,
                     EnumSet<Modifier> modifiers,
                     List<FieldDef> fields,
                     List<MethodDef> methods,
                     List<PropertyDef> properties,
                     List<AnnotationDef> annotations, List<TypeDef.TypeVariable> typeVariables,
                     List<TypeDef> superinterfaces) {
        super(name, modifiers, annotations);
        this.fields = fields;
        this.methods = methods;
        this.properties = properties;
        this.typeVariables = typeVariables;
        this.superinterfaces = superinterfaces;
    }

    public static ClassDefBuilder builder(String name) {
        return new ClassDefBuilder(name);
    }

    public List<FieldDef> getFields() {
        return fields;
    }

    public List<MethodDef> getMethods() {
        return methods;
    }

    public List<PropertyDef> getProperties() {
        return properties;
    }

    public List<TypeDef.TypeVariable> getTypeVariables() {
        return typeVariables;
    }

    public List<TypeDef> getSuperinterfaces() {
        return superinterfaces;
    }

    @Nullable
    public FieldDef findField(String name) {
        for (FieldDef field : fields) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    @NonNull
    public FieldDef getField(String name) {
        FieldDef field = findField(name);
        if (field == null) {
            throw new IllegalStateException("Class: " + name + " doesn't have a field: " + name);
        }
        return null;
    }

    /**
     * The class definition builder.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class ClassDefBuilder extends AbstractElementBuilder<ClassDefBuilder> {

        private final List<FieldDef> fields = new ArrayList<>();
        private final List<MethodDef> methods = new ArrayList<>();
        private final List<PropertyDef> properties = new ArrayList<>();
        private final List<TypeDef.TypeVariable> typeVariables = new ArrayList<>();
        private final List<TypeDef> superinterfaces = new ArrayList<>();

        private ClassDefBuilder(String name) {
            super(name);
        }

        public ClassDefBuilder addField(FieldDef field) {
            fields.add(field);
            return this;
        }

        public ClassDefBuilder addMethod(MethodDef method) {
            methods.add(method);
            return this;
        }

        public ClassDefBuilder addProperty(PropertyDef property) {
            properties.add(property);
            return this;
        }

        public ClassDefBuilder addTypeVariable(TypeDef.TypeVariable typeVariable) {
            typeVariables.add(typeVariable);
            return this;
        }

        public ClassDefBuilder addSuperinterface(TypeDef superinterface) {
            superinterfaces.add(superinterface);
            return this;
        }

        public ClassDef build() {
            return new ClassDef(name, modifiers, fields, methods, properties, annotations, typeVariables, superinterfaces);
        }

    }

}
