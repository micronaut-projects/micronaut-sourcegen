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

import java.util.List;
import java.util.Optional;

class MyRepository1Test {
    @Test
    public void test() throws Exception {
        MyRepository1Instance myInstance = new MyRepository1Instance();
        MyEntity1 myEntity1 = new MyEntity1();

        Assertions.assertInstanceOf(CrudRepository1.class, myInstance);

        Assertions.assertTrue(myInstance.findById(123L).isEmpty());

        myInstance.save(myEntity1);

        Assertions.assertEquals(myEntity1, myInstance.findById(123L).get());
        Assertions.assertEquals(myEntity1, myInstance.findAll().iterator().next());
    }

    static class MyRepository1Instance implements MyRepository1 {

        MyEntity1 myEntity1;

        @Override
        public Optional<MyEntity1> findById(Long id) {
            return Optional.ofNullable(myEntity1);
        }

        @Override
        public List<MyEntity1> findAll() {
            return List.of(myEntity1);
        }

        @Override
        public void save(MyEntity1 entity) {
            myEntity1 = entity;
        }
    }
}
