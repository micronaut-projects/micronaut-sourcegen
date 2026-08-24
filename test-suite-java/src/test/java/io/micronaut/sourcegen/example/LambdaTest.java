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

import static org.junit.jupiter.api.Assertions.assertEquals;

class LambdaTest {

    @Test
    public void testStatelessLambda() throws Exception {
        MyClassWithLambda owner = new MyClassWithLambda();
        assertEquals("ello!", owner.callLambda("Hello!"));
    }

    @Test
    public void testStatefulLambda() throws Exception {
        MyClassWithLambda owner = new MyClassWithLambda();
        owner.name = "Tree";
        assertEquals("prefix_ello!MyClassTree", owner.callStatefulLambda("Hello!"));
    }

    @Test
    public void testGenericLambda() throws Exception {
        MyClassWithLambda owner = new MyClassWithLambda();
        assertEquals("prefix_ello!", owner.callGenericLambda("Hello!"));
    }

    @Test
    public void testGenericLambda2() throws Exception {
        MyClassWithLambda owner = new MyClassWithLambda();
        assertEquals("prefix_ello!", owner.callGenericLambda2("Hello!"));
    }

    @Test
    public void testGenericLambdaAst() throws Exception {
        MyClassWithLambda owner = new MyClassWithLambda();
        assertEquals("prefix_ello!", owner.callGenericLambdaAst("Hello!"));
    }


    @Test
    public void testComparatorLambdaAst() {
        MyClassWithLambda owner = new MyClassWithLambda();
        assertEquals(0, owner.compareAst("a", "a"));
        assertEquals(-1, owner.compareAst("a", "b"));
        assertEquals(1, owner.compareAst("b", "a"));
    }

}
