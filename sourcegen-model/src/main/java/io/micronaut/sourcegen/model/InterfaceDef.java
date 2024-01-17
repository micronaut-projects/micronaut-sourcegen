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
public final class InterfaceDef extends AbstractElement implements ObjectDef {

    private final List<MethodDef> methods;
    private final List<PropertyDef> properties;
    private final List<TypeDef.TypeVariable> typeVariables;
    private final List<TypeDef> superinterfaces;

    private InterfaceDef(String name,
                         EnumSet<Modifier> modifiers,
                         List<MethodDef> methods,
                         List<PropertyDef> properties,
                         List<AnnotationDef> annotations,
                         List<String> javadoc,
                         List<TypeDef.TypeVariable> typeVariables,
                         List<TypeDef> superinterfaces) {
        super(name, modifiers, annotations, javadoc);
        this.methods = methods;
        this.properties = properties;
        this.typeVariables = typeVariables;
        this.superinterfaces = superinterfaces;
    }

    public static InterfaceDefBuilder builder(String name) {
        return new InterfaceDefBuilder(name);
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
        private final List<TypeDef.TypeVariable> typeVariables = new ArrayList<>();
        private final List<TypeDef> superinterfaces = new ArrayList<>();

        private InterfaceDefBuilder(String name) {
            super(name);
        }

        public InterfaceDefBuilder addMethod(MethodDef method) {
            methods.add(method);
            return this;
        }

        public InterfaceDefBuilder addProperty(PropertyDef property) {
            properties.add(property);
            return this;
        }

        public InterfaceDefBuilder addTypeVariable(TypeDef.TypeVariable typeVariable) {
            typeVariables.add(typeVariable);
            return this;
        }

        public InterfaceDefBuilder addSuperinterface(TypeDef superinterface) {
            superinterfaces.add(superinterface);
            return this;
        }

        public InterfaceDef build() {
            return new InterfaceDef(name, modifiers, methods, properties, annotations, javadoc, typeVariables, superinterfaces);
        }

    }

}
