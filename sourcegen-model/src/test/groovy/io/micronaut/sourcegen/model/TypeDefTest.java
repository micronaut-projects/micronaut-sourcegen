package io.micronaut.sourcegen.model;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.sourcegen.model.TypeDef.Array;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    /**
     * The default of a primitive is a constant of that primitive: an int zero where a char is expected is an Integer,
     * which a builder's `value == null ? 0 : value` cast to `char` fails on.
     */
    @ParameterizedTest(name = "the default of {0} is a {0} constant, boxed as {1}")
    @CsvSource({
        "byte, java.lang.Byte",
        "short, java.lang.Short",
        "char, java.lang.Character",
        "int, java.lang.Integer",
        "long, java.lang.Long",
        "float, java.lang.Float",
        "double, java.lang.Double",
        "boolean, java.lang.Boolean"
    })
    void defaultValueIsOfThePrimitiveType(String name, String wrapper) {
        ExpressionDef.Constant constant = TypeDef.Primitive.defaultValue(name);
        assertEquals(TypeDef.primitive(name), constant.type());
        assertEquals(wrapper, constant.value().getClass().getName());
    }

}
