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

    @Test
    public void testSelfTypeOfARecordWithoutVariables() {
        RecordDef recordDef = RecordDef.builder("com.example.Test").build();

        Assertions.assertEquals(ClassTypeDef.of("com.example.Test"), recordDef.asTypeDef());
    }

    @Test
    public void testSelfTypeOfAGenericRecordIsParameterized() {
        RecordDef recordDef = RecordDef.builder("com.example.Test")
            .addTypeVariable(TypeDef.variable("K"))
            .addTypeVariable(TypeDef.variable("V", TypeDef.of(Number.class)))
            .build();

        Assertions.assertEquals(
            TypeDef.parameterized(
                ClassTypeDef.of("com.example.Test"),
                TypeDef.variable("K"),
                TypeDef.variable("V", TypeDef.of(Number.class))
            ),
            recordDef.asTypeDef()
        );
    }

    @Test
    public void testTheSelfTypeIsWhatTypeDefThisResolvesTo() {
        RecordDef recordDef = RecordDef.builder("com.example.Test")
            .addTypeVariable(TypeDef.variable("T"))
            .build();

        Assertions.assertEquals(recordDef.asTypeDef(), recordDef.getContextualType(TypeDef.THIS));
    }

}
