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
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SignatureUtilsTest {

    @Test
    void omitsTheSignatureWhenTheDescriptorSaysEverything() {
        // A signature is only needed when a type carries generic information
        assertNull(SignatureUtils.getFieldSignature(null,
            FieldDef.builder("name", TypeDef.STRING).build()));
        assertNull(SignatureUtils.getMethodSignature(null,
            MethodDef.builder("run").addParameter("name", TypeDef.STRING).build()));
        assertNull(SignatureUtils.getInterfaceSignature(InterfaceDef.builder("example.Plain").build()));
        // A class signature is always built; the caller decides whether the class needs one
        assertEquals("Ljava/lang/Object;",
            SignatureUtils.getClassSignature(ClassDef.builder("example.Plain").build()));
    }

    @Test
    void writesClassSignaturesForTypeVariablesAndParameterizedSupertypes() {
        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(Number.class));
        ClassDef generic = ClassDef.builder("example.Holder")
            .addTypeVariable(variable)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(java.util.AbstractList.class), variable))
            .build();
        assertEquals("<T:Ljava/lang/Number;>Ljava/util/AbstractList<TT;>;",
            SignatureUtils.getClassSignature(generic));

        ClassDef withInterface = ClassDef.builder("example.Names")
            .addSuperinterface(TypeDef.parameterized(List.class, String.class))
            .build();
        assertEquals("Ljava/lang/Object;Ljava/util/List<Ljava/lang/String;>;",
            SignatureUtils.getClassSignature(withInterface));
    }

    @Test
    void writesInterfaceAndRecordSignatures() {
        TypeDef.TypeVariable variable = TypeDef.variable("T");
        InterfaceDef generic = InterfaceDef.builder("example.Contract")
            .addTypeVariable(variable)
            .build();
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Object;",
            SignatureUtils.getInterfaceSignature(generic));

        RecordDef record = RecordDef.builder("example.Pair")
            .addTypeVariable(variable)
            .addProperty(PropertyDef.builder("value").ofType(variable).build())
            .build();
        assertEquals("<T:Ljava/lang/Object;>Ljava/lang/Record;",
            SignatureUtils.getRecordSignature(record));
    }

    @Test
    void writesFieldAndMethodSignaturesIncludingTypeVariables() {
        assertEquals("Ljava/util/List<Ljava/lang/String;>;",
            SignatureUtils.getFieldSignature(null,
                FieldDef.builder("values", TypeDef.parameterized(List.class, String.class)).build()));

        TypeDef.TypeVariable variable = TypeDef.variable("T", TypeDef.of(Number.class));
        MethodDef method = MethodDef.builder("first")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(variable)
            .addParameter("values", TypeDef.parameterized(ClassTypeDef.of(List.class), variable))
            .returns(variable)
            .build();
        assertEquals("<T:Ljava/lang/Number;>(Ljava/util/List<TT;>;)TT;",
            SignatureUtils.getMethodSignature(null, method));
    }
}
