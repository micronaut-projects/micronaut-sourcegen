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
import io.micronaut.core.naming.NameUtils;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The interface definition.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public final class InterfaceDef extends AbstractElement implements ObjectDefinition {

    private final List<MethodDef> methods;
    private final List<PropertyDef> properties;

    private InterfaceDef(String name,
                         EnumSet<Modifier> modifiers,
                         List<MethodDef> methods,
                         List<PropertyDef> properties,
                         List<AnnotationDef> annotations) {
        super(name, modifiers, annotations);
        this.methods = methods;
        this.properties = properties;
    }

    public static InterfaceDefBuilder builder(String name) {
        return new InterfaceDefBuilder(name);
    }

    public ClassTypeDef asTypeDef() {
        return ClassTypeDef.of(getName());
    }

    public String getPackageName() {
        return NameUtils.getPackageName(name);
    }

    public String getSimpleName() {
        return NameUtils.getSimpleName(name);
    }

    public List<MethodDef> getMethods() {
        return methods;
    }

    public List<PropertyDef> getProperties() {
        return properties;
    }

    /**
     * The interface definition builder.
     *
     * @author Denis Stepanov
     * @since 1.0
     */
    @Experimental
    public static final class InterfaceDefBuilder extends AbstractElementBuilder<InterfaceDefBuilder> {

        private final List<MethodDef> methods = new ArrayList<>();
        private final List<PropertyDef> properties = new ArrayList<>();

        private InterfaceDefBuilder(String name) {
            super(name);
        }

        public void addMethod(MethodDef method) {
            methods.add(method);
        }

        public void addProperty(PropertyDef property) {
            properties.add(property);
        }

        public InterfaceDef build() {
            return new InterfaceDef(name, modifiers, methods, properties, annotations);
        }

    }

}
