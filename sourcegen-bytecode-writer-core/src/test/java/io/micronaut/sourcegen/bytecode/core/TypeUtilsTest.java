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

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypeUtilsTest {

    @Test
    void describesEveryPrimitiveAndVoid() {
        assertEquals("V", TypeUtils.getDescriptor(TypeDef.VOID, null));
        assertEquals("Z", TypeUtils.getDescriptor(TypeDef.Primitive.BOOLEAN, null));
        assertEquals("B", TypeUtils.getDescriptor(TypeDef.Primitive.BYTE, null));
        assertEquals("C", TypeUtils.getDescriptor(TypeDef.Primitive.CHAR, null));
        assertEquals("S", TypeUtils.getDescriptor(TypeDef.Primitive.SHORT, null));
        assertEquals("I", TypeUtils.getDescriptor(TypeDef.Primitive.INT, null));
        assertEquals("J", TypeUtils.getDescriptor(TypeDef.Primitive.LONG, null));
        assertEquals("F", TypeUtils.getDescriptor(TypeDef.Primitive.FLOAT, null));
        assertEquals("D", TypeUtils.getDescriptor(TypeDef.Primitive.DOUBLE, null));
    }

    @Test
    void describesArraysAnnotatedTypesAndParameterizedTypes() {
        assertEquals("[I", TypeUtils.getDescriptor(TypeDef.Primitive.INT.array(), null));
        assertEquals("[[Ljava/lang/String;",
            TypeUtils.getDescriptor(new TypeDef.Array(TypeDef.STRING, 2, false), null));
        // A type's annotations are written separately and play no part in its erasure
        AnnotationDef marker = AnnotationDef.builder(ClassTypeDef.of("example.Marker")).build();
        assertEquals("Ljava/lang/String;",
            TypeUtils.getDescriptor(TypeDef.STRING.annotated(List.of(marker)), null));
        assertEquals("Ljava/util/List;",
            TypeUtils.getDescriptor(TypeDef.parameterized(List.class, String.class), null));
    }

    @Test
    void erasesWildcardsAndTypeVariablesToTheirFirstNonObjectBound() {
        assertEquals("Ljava/lang/Object;",
            TypeUtils.getDescriptor(TypeDef.wildcard(), null));
        assertEquals("Ljava/lang/Number;",
            TypeUtils.getDescriptor(TypeDef.variable("T", TypeDef.of(Number.class)), null));
        // An unbounded reference resolves through the declaring type's own bounds
        TypeDef.TypeVariable declared = TypeDef.variable("T", TypeDef.of(Number.class));
        ClassDef classDef = ClassDef.builder("example.Holder").addTypeVariable(declared).build();
        assertEquals("Ljava/lang/Number;",
            TypeUtils.getDescriptor(TypeDef.variable("T"), classDef));
        InterfaceDef interfaceDef = InterfaceDef.builder("example.Contract").addTypeVariable(declared).build();
        assertEquals("Ljava/lang/Number;",
            TypeUtils.getDescriptor(TypeDef.variable("T"), interfaceDef));
        // A variable the definition does not declare erases to Object
        assertEquals("Ljava/lang/Object;",
            TypeUtils.getDescriptor(TypeDef.variable("U"), classDef));
        assertEquals("Ljava/lang/Object;",
            TypeUtils.getDescriptor(TypeDef.variable("T"), null));
    }

    @Test
    void describesArrayClassNamesInBothTheirBinaryAndSourceForms() {
        // Class#getName of an array is already a descriptor, unlike a source-style name
        assertEquals("[Lcom/example/Item;", TypeUtils.getDescriptor("[Lcom.example.Item;"));
        assertEquals("[[I", TypeUtils.getDescriptor("[[I"));
        assertEquals("[Lcom/example/Item;", TypeUtils.getDescriptor("com.example.Item[]"));
        assertEquals("Lcom/example/Item;", TypeUtils.getDescriptor("com.example.Item"));
    }

    @Test
    void buildsInternalNamesAndMethodDescriptors() {
        assertEquals("com/example/Item", TypeUtils.getInternalName("com.example.Item"));
        assertEquals("com/example/Item", TypeUtils.getInternalName("com.example.Item[]"));

        MethodDef method = MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("name", TypeDef.STRING)
            .addParameter("count", TypeDef.Primitive.INT)
            .build();
        assertEquals("(Ljava/lang/String;I)V", TypeUtils.getMethodDescriptor(null, method));

        MethodDef constructor = MethodDef.constructor().build();
        assertEquals("()V", TypeUtils.getMethodDescriptor(null, constructor));
    }
}
