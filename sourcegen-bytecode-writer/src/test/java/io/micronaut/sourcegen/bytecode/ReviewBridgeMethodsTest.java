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
package io.micronaut.sourcegen.bytecode;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Review probe: the class the ASM writer produces for an erased override of a compiled generic method dispatches
 * through the compiled base.
 *
 * @since 2.2.2
 */
class ReviewBridgeMethodsTest {

    /**
     * A compiled generic method whose own variable lists {@code Object} ahead of its real bound: javac erases
     * {@code U} to {@code Object} (JLS 4.6), so the class file declares {@code pick(Object, Object)}.
     */
    public static class ObjectBoundBase<T> {
        public <U extends Object & Comparable<U>> T pick(T value, U other) {
            return null;
        }
    }

    /**
     * The child overrides with javac's erasure as a member of `ObjectBoundBase<String>`, `String pick(String, Object)`,
     * which needs the bridge `pick(Object, Object)`. The walk erases `U` to `Comparable`, so no bridge is written and
     * a call through the base reaches the base: null instead of "child", silently.
     */
    @Test
    @SuppressWarnings("unchecked")
    void erasedOverrideOfAMethodVariableListingObjectBeforeItsBoundDispatches() throws Exception {
        ClassDef child = ClassDef.builder("example.ObjectBoundChild").addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ObjectBoundBase.class, String.class))
            .addMethod(MethodDef.builder("pick").addModifiers(Modifier.PUBLIC).overrides()
                .addParameter("value", TypeDef.STRING).addParameter("other", TypeDef.OBJECT).returns(TypeDef.STRING)
                .build((aThis, p) -> ExpressionDef.constant("child").returning()))
            .build();
        byte[] bytecode = new ByteCodeWriter().write(child);
        Class<?> loaded = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(child.getName())) {
                    return defineClass(name, bytecode, 0, bytecode.length);
                }
                throw new ClassNotFoundException(name);
            }
        }.loadClass(child.getName());
        ObjectBoundBase<String> base = (ObjectBoundBase<String>) loaded.getConstructor().newInstance();

        assertEquals("child", base.pick("value", 1));
    }
}
