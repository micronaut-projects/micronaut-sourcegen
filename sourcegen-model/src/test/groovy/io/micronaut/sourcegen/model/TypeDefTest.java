package io.micronaut.sourcegen.model;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.TypeDef.Array;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeDefTest {

    @Test
    void arraysTest() {
        TypeDef type = TypeDef.STRING;
        TypeDef.Array array = type.array();

        assertEquals(1, array.dimensions());
        assertEquals(2, array.array().dimensions());
        assertEquals(3, array.array(2).dimensions());

        TypeDef array2 = TypeDef.of(ClassElement.of(int[][].class));
        assertTrue(array2.isArray());
        assertEquals(2, ((Array) array2).dimensions());

    }

}
