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
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The abstract builder that is used for specific types: interfaces, classes, records or enums.
 *
 * @param <ThisType> The type of this builder
 * @author Andriy Dmytruk
 * @since 1.3
 */
@Experimental
public sealed class ObjectDefBuilder<ThisType>
    extends AbstractElementBuilder<ThisType>
    permits ClassDef.ClassDefBuilder, InterfaceDef.InterfaceDefBuilder,
    RecordDef.RecordDefBuilder, EnumDef.EnumDefBuilder {

    protected final List<MethodDef> methods = new ArrayList<>();
    protected final List<PropertyDef> properties = new ArrayList<>();
    protected final List<TypeDef> superinterfaces = new ArrayList<>();
    protected final List<ObjectDef> innerTypes = new ArrayList<>();

    protected ObjectDefBuilder(String name) {
        super(name);
    }

    /**
     * Add a method.
     *
     * @param method The method.
     * @return The builder
     */
    @NonNull
    public final ThisType addMethod(@NonNull MethodDef method) {
        methods.add(method);
        return thisInstance;
    }

    /**
     * Add methods.
     *
     * @param methods The method.s
     * @return The builder
     */
    @NonNull
    public final ThisType addMethods(@NonNull Collection<MethodDef> methods) {
        this.methods.addAll(methods);
        return thisInstance;
    }

    /**
     * Add a property.
     *
     * @param property The property.
     * @return The builder
     */
    @NonNull
    public final ThisType addProperty(@NonNull PropertyDef property) {
        properties.add(property);
        return thisInstance;
    }

    /**
     * Add a super interface.
     *
     * @param superinterface The interface.
     * @return The builder
     */
    @NonNull
    public final ThisType addSuperinterface(@NonNull TypeDef superinterface) {
        superinterfaces.add(superinterface);
        return thisInstance;
    }

    /**
     * Add super interfaces.
     *
     * @param superinterfaces The interfaces.
     * @return The builder
     */
    @NonNull
    public final ThisType addSuperinterfaces(@NonNull Collection<TypeDef> superinterfaces) {
        this.superinterfaces.addAll(superinterfaces);
        return thisInstance;
    }

    /**
     * Add an inner type.
     *
     * @param innerDef The inner definition.
     * @return The builder
     */
    @NonNull
    public final ThisType addInnerType(@NonNull ObjectDef innerDef) {
        ClassTypeDef innerType = innerDef.asTypeDef();
        String newName = ClassTypeDef.of(name).getCanonicalName() + "$" + innerType.getSimpleName();
        innerTypes.add(innerDef.withClassName(new ClassTypeDef.ClassName(newName, true)));
        return thisInstance;
    }

    /**
     * Add an inner types.
     *
     * @param innerDefs The inner definitions.
     * @return The builder
     */
    @NonNull
    public final ThisType addInnerType(@NonNull Collection<ObjectDef> innerDefs) {
        innerDefs.forEach(this::addInnerType);
        return thisInstance;
    }

}
