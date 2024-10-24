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

import java.util.ArrayList;
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
    protected final List<TypeDef> superinterfaces = new ArrayList<>();

    protected ObjectDefBuilder(String name) {
        super(name);
    }

    public final ThisType addMethod(MethodDef method) {
        methods.add(method);
        return thisInstance;
    }

    public final ThisType addSuperinterface(TypeDef superinterface) {
        superinterfaces.add(superinterface);
        return thisInstance;
    }

}
