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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MyInterface1Test {
    @Test
    public void test() throws Exception {
        MyInstance myInstance = new MyInstance();
        Assertions.assertEquals(123L, myInstance.findLong());
        myInstance.saveString("abc");
        Assertions.assertEquals("abc", myInstance.myString);
    }

    static class MyInstance implements MyInterface1 {

        String myString;

        @Override
        public Long findLong() {
            return 123L;
        }

        @Override
        public void saveString(String myString) {
            this.myString = myString;
        }
    }
}
