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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserTest {

    //tag::test[]
    @Test
    public void testSimple() {
        User user = new UserSuperBuilder()
            .id(123L)
            .name("Denis")
            .role("READ")
            .role("WRITE")
            .property("key", "value")
            .build();
        assertEquals(123L, user.id());
        assertEquals("Denis", user.name());
        assertEquals(List.of("READ", "WRITE"), user.roles());
        assertEquals(Map.of("key", "value"), user.properties());
    }

    @Test
    public void testAddAll() {
        User user = new UserSuperBuilder()
            .id(123L)
            .name("Denis")
            .role("READ")
            .role("WRITE")
            .roles(List.of("ROLES_ADMIN", "ROLES_USER"))
            .property("key", "value")
            .properties(Map.of("key1", "value1", "key2", "value2"))
            .build();
        assertEquals(123L, user.id());
        assertEquals("Denis", user.name());
        assertEquals(List.of("READ", "WRITE", "ROLES_ADMIN", "ROLES_USER"), user.roles());
        assertEquals(Map.of("key", "value", "key1", "value1", "key2", "value2"), user.properties());
    }

    @Test
    public void testClear() {
        User user = new UserSuperBuilder()
            .id(123L)
            .name("Denis")
            .role("READ")
            .role("WRITE")
            .clearRoles()
            .roles(List.of("ROLES_ADMIN", "ROLES_USER"))
            .property("key", "value")
            .clearProperties()
            .properties(Map.of("key1", "value1", "key2", "value2"))
            .build();
        assertEquals(123L, user.id());
        assertEquals("Denis", user.name());
        assertEquals(List.of("ROLES_ADMIN", "ROLES_USER"), user.roles());
        assertEquals(Map.of("key1", "value1", "key2", "value2"), user.properties());
    }
    //end::test[]


}
