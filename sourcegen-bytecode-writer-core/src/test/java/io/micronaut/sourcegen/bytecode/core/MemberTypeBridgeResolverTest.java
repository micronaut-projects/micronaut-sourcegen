/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * bridge resolution.
 */
class MemberTypeBridgeResolverTest {

    abstract static class PkgBase<T> {
        abstract void take(T value);
    }

    /**
     * A member type overrides a package-private generic method of a class of its own package. The package of a
     * member definition is read as `pkg.Outer` - `ClassTypeDef.getPackageName()` cuts the canonical name at the last
     * dot - so the inherited method is taken for one of another package and no `take(Object)` bridge is resolved:
     * a call through `PkgBase` would not reach the override. The top level type gets its bridge. The old resolver
     * compared the packages the same way, so this is no regression of the rebuild, but the same comparison now also
     * decides the source override ({@code OverrideResolver}).
     */
    @Test
    void memberTypeOverridesAPackagePrivateMethodOfItsPackage() {
        String packageName = MemberTypeBridgeResolverTest.class.getPackageName();
        MethodDef take = MethodDef.builder("take").addParameter("value", TypeDef.STRING).build();
        ClassDef member = ClassDef.builder("Member").addModifiers(Modifier.STATIC)
            .superclass(TypeDef.parameterized(PkgBase.class, String.class))
            .addMethod(take).build();
        ClassDef top = ClassDef.builder(packageName + ".MemberTop")
            .superclass(TypeDef.parameterized(PkgBase.class, String.class))
            .addMethod(take).addInnerType(member).build();
        ObjectDef storedMember = top.getInnerTypes().get(0);

        assertEquals(1, BridgeResolver.resolve(top, take).size());
        assertEquals(1, BridgeResolver.resolve(storedMember, storedMember.getMethods().get(0)).size());
    }
}
