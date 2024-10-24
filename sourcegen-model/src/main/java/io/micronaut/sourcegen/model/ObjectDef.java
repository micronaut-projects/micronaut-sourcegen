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
import java.util.List;
import java.util.Set;

/**
 * The abstract class representing a type: class, enum, interface or record.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public abstract sealed class ObjectDef extends AbstractElement permits ClassDef, EnumDef, InterfaceDef, RecordDef {

    private final List<MethodDef> methods;
    private final List<TypeDef> superinterfaces;

    ObjectDef(
            String name, Set<Modifier> modifiers, List<AnnotationDef> annotations,
            List<String> javadoc, List<MethodDef> methods, List<TypeDef> superinterfaces
    ) {
        super(name, modifiers, annotations, javadoc);
        this.methods = methods;
        this.superinterfaces = superinterfaces;
    }

    public final List<MethodDef> getMethods() {
        return methods;
    }

    public final List<TypeDef> getSuperinterfaces() {
        return superinterfaces;
    }

    public final String getPackageName() {
        return NameUtils.getPackageName(getName());
    }

    public final String getSimpleName() {
        return NameUtils.getSimpleName(getName());
    }

    /**
     * Get the type definition for this type.
     *
     * @return The type definition
     */
    public ClassTypeDef asTypeDef() {
        return ClassTypeDef.of(getName());
    }

}
