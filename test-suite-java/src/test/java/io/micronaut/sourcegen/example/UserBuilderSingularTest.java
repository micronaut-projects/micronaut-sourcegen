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

import io.micronaut.core.util.CollectionUtils;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserBuilderSingularTest {

    private static UserBuilderSingular buildUserSingle() {
        return new UserBuilderSingularBuilder()
            .id(123L)
            .name("Denis")
            .iterable("iterable1")
            .iterable("iterable2")
            .collection("collection1")
            .collection("collection2")
            .list("list1")
            .list("list2")
            .set("set1")
            .set("set2")
            .sortedSet("sortedSet1")
            .sortedSet("sortedSet2")
            .map("A", 1)
            .map("B", 2)
            .sortedMap("AS", 1)
            .sortedMap("BS", 2)
            .build();
    }

    private static UserBuilderSingular buildUserComplex() {
        return new UserBuilderSingularBuilder()
            .id(123L)
            .name("Denis")
            .iterable("iterable1")
            .iterable("iterable2")
            .iterables(List.of("iterable3", "iterable4"))
            .collection("collection1")
            .collection("collection2")
            .collections(List.of("collection3", "collection4"))
            .list("list1")
            .list("list2")
            .lists(List.of("list3", "list4"))
            .set("set1")
            .set("set2")
            .sets(List.of("set3", "set4"))
            .sortedSet("sortedSet1")
            .sortedSet("sortedSet2")
            .sortedSets(List.of("sortedSet3", "sortedSet4"))
            .map("A", 1)
            .map("B", 2)
            .maps(Map.of("C", 3))
            .maps(Map.of("D", 4))
            .sortedMap("AS", 1)
            .sortedMap("BS", 2)
            .sortedMaps(Map.of("CS", 3, "DS", 4))
            .build();
    }


    private static UserBuilderSingular buildUserCleanup() {
        return new UserBuilderSingularBuilder()
            .id(123L)
            .name("Denis")
            .iterable("iterable1")
            .iterable("iterable2")
            .iterables(List.of("iterable3", "iterable4"))
            .collection("collection1")
            .collection("collection2")
            .collections(List.of("collection3", "collection4"))
            .list("list1")
            .list("list2")
            .lists(List.of("list3", "list4"))
            .set("set1")
            .set("set2")
            .sets(List.of("set3", "set4"))
            .sortedSet("sortedSet1")
            .sortedSet("sortedSet2")
            .sortedSets(List.of("sortedSet3", "sortedSet4"))
            .map("A", 1)
            .map("B", 2)
            .maps(Map.of("C", 3, "D", 4))
            .sortedMap("AS", 1)
            .sortedMap("BS", 2)
            .sortedMaps(Map.of("CS", 3, "DS", 4))
            .clearCollections()
            .clearIterables()
            .clearLists()
            .clearSets()
            .clearSortedSets()
            .clearMaps()
            .clearSortedMaps()
            .build();
    }

    @Test
    public void testCombinationsSimple() {
        UserBuilderSingular user = buildUserSingle();
        assertEquals(123L, user.id());
        assertEquals("Denis", user.name());
        assertEquals(List.of("iterable1", "iterable2"), CollectionUtils.iterableToList(user.iterables()));
        assertEquals(List.of("collection1", "collection2"), user.collections());
        assertEquals(List.of("list1", "list2"), user.lists());
        assertEquals(Set.of("set1", "set2"), user.sets());
        assertEquals(new TreeSet<>(Set.of("sortedSet1", "sortedSet2")), user.sortedSets());
        assertEquals(Map.of("A", 1, "B", 2), user.maps());
        assertEquals(Map.of("AS", 1, "BS", 2), user.sortedMaps());
    }

    @Test
    public void testCombinationsComplex() {
        UserBuilderSingular user = buildUserComplex();
        assertEquals(123L, user.id());
        assertEquals("Denis", user.name());
        assertEquals(List.of("iterable1", "iterable2", "iterable3", "iterable4"), CollectionUtils.iterableToList(user.iterables()));
        assertEquals(List.of("collection1", "collection2", "collection3", "collection4"), user.collections());
        assertEquals(List.of("list1", "list2", "list3", "list4"), user.lists());
        assertEquals(Set.of("set1", "set2", "set3", "set4"), user.sets());
        assertEquals(new TreeSet<>(Set.of("sortedSet1", "sortedSet2", "sortedSet3", "sortedSet4")), user.sortedSets());
        assertEquals(Map.of("A", 1, "B", 2, "C", 3, "D", 4), user.maps());
        assertEquals(Map.of("AS", 1, "BS", 2, "CS", 3, "DS", 4), user.sortedMaps());
    }

    @Test
    public void testImmutable() {
        UserBuilderSingular user = buildUserSingle();
        assertThrowsExactly(UnsupportedOperationException.class, () -> {
            user.lists().add("x");
        });
        assertThrowsExactly(UnsupportedOperationException.class, () -> {
            user.sets().add("x");
        });
        assertThrowsExactly(UnsupportedOperationException.class, () -> {
            user.sortedSets().add("x");
        });
        assertThrowsExactly(UnsupportedOperationException.class, () -> {
            user.collections().add("x");
        });
        assertThrowsExactly(UnsupportedOperationException.class, () -> {
            user.iterables().iterator().remove();
        });
        assertThrowsExactly(UnsupportedOperationException.class, () -> {
            user.maps().put("xx", 11);
        });
        assertThrowsExactly(UnsupportedOperationException.class, () -> {
            user.sortedMaps().put("xx", 11);
        });
    }

    @Test
    public void testNotNull() {
        UserBuilderSingular user = new UserBuilderSingularBuilder().build();
        assertNotNull(user.iterables());
        assertNotNull(user.collections());
        assertNotNull(user.lists());
        assertNotNull(user.sets());
        assertNotNull(user.sortedSets());
        assertFalse(user.iterables().iterator().hasNext());
        assertTrue(user.collections().isEmpty());
        assertTrue(user.lists().isEmpty());
        assertTrue(user.sets().isEmpty());
        assertTrue(user.sortedSets().isEmpty());
        assertTrue(user.maps().isEmpty());
        assertTrue(user.sortedMaps().isEmpty());
    }

    @Test
    public void mapOrder() {
        UserBuilderSingular user = buildUserComplex();
        Map<String, Integer> maps = user.maps();
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(maps.entrySet());
        assertEquals("A", entries.get(0).getKey());
        assertEquals("B", entries.get(1).getKey());
        assertEquals("C", entries.get(2).getKey());
        assertEquals("D", entries.get(3).getKey());
    }

    @Test
    public void sortedMapOrder() {
        UserBuilderSingular user = buildUserComplex();
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(user.sortedMaps().entrySet());
        assertEquals("AS", entries.get(0).getKey());
        assertEquals("BS", entries.get(1).getKey());
        assertEquals("CS", entries.get(2).getKey());
        assertEquals("DS", entries.get(3).getKey());
    }

    @Test
    public void testCleanup() {
        UserBuilderSingular user = buildUserCleanup();
        assertNotNull(user.iterables());
        assertNotNull(user.collections());
        assertNotNull(user.lists());
        assertNotNull(user.sets());
        assertNotNull(user.sortedSets());
        assertFalse(user.iterables().iterator().hasNext());
        assertTrue(user.collections().isEmpty());
        assertTrue(user.lists().isEmpty());
        assertTrue(user.sets().isEmpty());
        assertTrue(user.sortedSets().isEmpty());
        assertTrue(user.maps().isEmpty());
        assertTrue(user.sortedMaps().isEmpty());
    }

}
