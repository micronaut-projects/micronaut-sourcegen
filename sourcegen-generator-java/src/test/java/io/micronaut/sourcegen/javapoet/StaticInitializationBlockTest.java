package io.micronaut.sourcegen.javapoet;

import io.micronaut.sourcegen.javapoet.write.AbstractWriteTest;
import io.micronaut.sourcegen.model.*;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StaticInitializationBlockTest extends AbstractWriteTest {

    @Test
    public void testSimpleStaticInitializer() throws IOException {

        StatementDef defineNewContext = new StatementDef.DefineAndAssign(new VariableDef.Local("var0", TypeDef.of(Context.class)), ClassTypeDef.of(Context.class).instantiate());
        StatementDef staticBlock = StatementDef.multi(List.of(defineNewContext));

        ClassDef classDef = ClassDef.builder("StaticInitializationBlockTest")
            .addModifiers(Modifier.PUBLIC)
            .addStaticInitializer(staticBlock)
            .build();

        String data = writeClass(classDef);

        assertEquals("""
public class StaticInitializationBlockTest {
  static {
    io.micronaut.sourcegen.javapoet.StaticInitializationBlockTest.Context var0 = new io.micronaut.sourcegen.javapoet.StaticInitializationBlockTest.Context();
  }
}
""", data);
    }

    private static class Context {
        public Context() {
        }
    }
}
