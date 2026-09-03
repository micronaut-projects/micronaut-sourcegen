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
import java.util.EnumSet;
import java.util.List;

/**
 * The abstract class representing a type: class, enum, interface or record.
 *
 * @author Denis Stepanov
 * @since 1.0
 */
@Experimental
public abstract sealed class ObjectDef extends AbstractElement permits ClassDef, EnumDef, InterfaceDef, RecordDef, AnnotationObjectDef {

    protected final ClassTypeDef.ClassName className;
    protected final List<MethodDef> methods;
    protected final List<PropertyDef> properties;
    protected final List<TypeDef> superinterfaces;
    protected final List<ObjectDef> innerTypes;

    ObjectDef(
        ClassTypeDef.ClassName className,
        EnumSet<Modifier> modifiers,
        List<AnnotationDef> annotations,
        List<String> javadoc,
        List<MethodDef> methods,
        List<PropertyDef> properties,
        List<TypeDef> superinterfaces,
        List<ObjectDef> innerTypes,
        boolean synthetic
    ) {
        super(className.getName(), modifiers, annotations, javadoc, synthetic);
        this.className = className;
        this.methods = methods;
        this.properties = properties;
        this.superinterfaces = superinterfaces;
        this.innerTypes = innerTypes;
    }

    public final List<MethodDef> getMethods() {
        return methods;
    }

    public final List<PropertyDef> getProperties() {
        return properties;
    }

    public final List<TypeDef> getSuperinterfaces() {
        return superinterfaces;
    }

    public final String getPackageName() {
        return className.getPackageName();
    }

    public final String getSimpleName() {
        return className.getSimpleName();
    }

    public final List<ObjectDef> getInnerTypes() {
        return innerTypes;
    }

    /**
     * Creates a copy of this definition with a new class name.
     *
     * @param className    The class name
     * @return the copy of this object definition with a new class name
     * @since 1.5
     */
    public abstract ObjectDef withClassName(ClassTypeDef.ClassName className);

    /**
     * Rebase member type names when their enclosing definition is itself moved below another type.
     *
     * @param className The new name of the enclosing definition
     * @param innerTypes Its member types
     * @return The member types with names rooted at the new enclosing name
     */
    static List<ObjectDef> rebaseInnerTypes(ClassTypeDef.ClassName className, List<ObjectDef> innerTypes) {
        return innerTypes.stream()
            .map(innerType -> innerType.withClassName(new ClassTypeDef.ClassName(
                className.getName() + "$" + innerType.getSimpleName(),
                true
            )))
            .toList();
    }

    /**
     * Get the type definition for this type.
     *
     * @return The type definition
     */
    public ClassTypeDef asTypeDef() {
        return ClassTypeDef.of(this);
    }

    /**
     * Get the actual contextual type.
     *
     * @param typeDef The type
     * @return The contextual type or original type
     * @since 1.5
     */
    public TypeDef getContextualType(TypeDef typeDef) {
        if (typeDef == TypeDef.THIS) {
            return asTypeDef();
        } else if (typeDef == TypeDef.SUPER) {
            if (this instanceof ClassDef classDef) {
                if (classDef.getSuperclass() == null) {
                    return TypeDef.of(Object.class);
                }
                return classDef.getSuperclass();
            } else if (this instanceof EnumDef) {
                return ClassTypeDef.of(Enum.class);
            } else if (this instanceof InterfaceDef interfaceDef) {
                throw new IllegalStateException("Super class is not supported for interface def: " + interfaceDef);
            }
        }
        return typeDef;
    }

    /**
     * Get a contextual type (converts this or super type to appropriate one).
     *
     * @param objectDef The object def
     * @param typeDef   The type def
     * @return the contextual type or type def provider
     * @since 1.4
     */
    public static TypeDef getContextualType(@Nullable ObjectDef objectDef, TypeDef typeDef) {
        if (objectDef == null) {
            if ((typeDef == TypeDef.THIS || typeDef == TypeDef.SUPER)) {
                throw new IllegalStateException("Cannot determine type: " + typeDef + " because object def is null");
            }
            return typeDef;
        }
        return objectDef.getContextualType(typeDef);
    }

}
