package io.micronaut.sourcegen.model;

import io.micronaut.annotation.processing.visitor.JavaVisitorContext;
import io.micronaut.inject.ast.PropertyElement;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;


class RecordDefTest {

    @Test
    public void testGetBeanProperties() {
        RecordDef recordDef = RecordDef.builder("com.example.Test")
            .addProperty(PropertyDef.builder("name").ofType(TypeDef.STRING)
                .build())
            .addProperty(PropertyDef.builder("list").ofType(
                TypeDef.parameterized(
                    ClassTypeDef.of(List.class),
                    TypeDef.STRING
                ))
                .build()
            )
            .build();

        List<PropertyElement> properties = recordDef.getBeanProperties(new JavaVisitorContext(
            null, null, null, null, null, null, null, null, null
        ));
        Assertions.assertEquals(2, properties.size());
        Assertions.assertEquals(String.class.getName(), properties.get(0).getType().getName());
    }

}
