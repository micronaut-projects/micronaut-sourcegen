/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.example;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionsTest {

    @Test
    public void testEqualsStructurally() {
        Expressions owner = new Expressions();
        assertTrue(owner.equalsStructurally("hello", "hello"));
        assertFalse(owner.equalsStructurally("hello", "hola"));
        assertFalse(owner.equalsStructurally(null, "hola"));
        assertFalse(owner.equalsStructurally("hello", null));
        assertTrue(owner.equalsStructurally(null, null));
    }

}
