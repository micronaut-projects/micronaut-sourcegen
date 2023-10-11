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
import io.micronaut.core.naming.NameUtils;

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
public final class ClassDef extends AbstractElement {

    private final List<FieldDef> fields;
    private final List<MethodDef> methods;
    private final List<PropertyDef> properties;

    private ClassDef(String name,
                     EnumSet<Modifier> modifiers,
                     List<FieldDef> fields,
                     List<MethodDef> methods,
                     List<PropertyDef> properties,
                     List<AnnotationDef> annotations) {
        super(name, modifiers, annotations);
        this.fields = fields;
        this.methods = methods;
        this.properties = properties;
    }

    public static ClassDefBuilder builder(String name) {
        return new ClassDefBuilder(name);
    }

    public TypeDef asTypeDef() {
        return TypeDef.of(this);
    }

    public String getPackageName() {
        return NameUtils.getPackageName(name);
    }

    public String getSimpleName() {
        return NameUtils.getSimpleName(name);
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

        private ClassDefBuilder(String name) {
            super(name);
        }

        public void addField(FieldDef field) {
            fields.add(field);
        }

        public void addMethod(MethodDef method) {
            methods.add(method);
        }

        public void addProperty(PropertyDef property) {
            properties.add(property);
        }

        public ClassDef build() {
            return new ClassDef(name, modifiers, fields, methods, properties, annotations);
        }

    }

}
