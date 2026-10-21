package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.MethodReferenceExpression;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests every kind of method reference as it is rendered into Java source.
 */
public class MethodReferenceWriteTest extends AbstractWriteTest {

    private static final ClassTypeDef OWNER = ClassTypeDef.of("test.Owner");

    private static ClassTypeDef stringFunction() {
        return InterfaceDef.builder("test.StringFunction")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("context", TypeDef.STRING)
                .returns(TypeDef.STRING)
                .build())
            .build()
            .asTypeDef();
    }

    private static String render(MethodReferenceExpression reference) throws IOException {
        return writeClass(ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassTypeDef.of("test.StringFunction"))
                .build((aThis, params) -> reference.returning()))
            .build());
    }

    @Test
    public void staticMethodReference() throws IOException {
        MethodDef shout = MethodDef.builder("shout")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();

        assertEquals("""
package test;

public class MyClass {
  public StringFunction evaluate() {
    return Owner::shout;
  }
}
            """, render(stringFunction().staticMethodReference(OWNER, shout)));
    }

    @Test
    public void aNamedReferenceRendersOnceBoundToAReceiver() throws IOException {
        MethodDef concat = TypeDef.STRING.findDeclaredMethods("concat", 1).get(0);

        assertEquals("""
package test;

public class MyClass {
  public StringFunction evaluate() {
    return "prefix_"::concat;
  }
}
            """, render(stringFunction().methodReference(ExpressionDef.constant("prefix_"), concat)));
    }

    @Test
    public void boundInstanceMethodReference() throws IOException {
        assertEquals("""
package test;

public class MyClass {
  public StringFunction evaluate() {
    return "prefix_"::concat;
  }
}
            """, render(stringFunction().methodReference(ExpressionDef.constant("prefix_"), "concat")));
    }

    @Test
    public void constructorReference() throws IOException {
        MethodDef constructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.STRING)
            .build();

        assertEquals("""
package test;

public class MyClass {
  public StringFunction evaluate() {
    return Owner::new;
  }
}
            """, render(stringFunction().constructorReference(OWNER, constructor)));
    }

    @Test
    public void aReceiverThatIsNotAPrimaryExpressionIsParenthesised() throws IOException {
        // A conditional is not a valid target of :: on its own
        ExpressionDef receiver = new ExpressionDef.IfElse(
            ExpressionDef.trueValue(),
            ExpressionDef.constant("prefix_"),
            ExpressionDef.constant("suffix_")
        );

        assertEquals("""
package test;

public class MyClass {
  public StringFunction evaluate() {
    return (true ? "prefix_" : "suffix_")::concat;
  }
}
            """, render(stringFunction().methodReference(receiver, "concat")));
    }

    @Test
    public void aStaticMethodCannotBeReferencedOnAnInstance() {
        MethodDef shout = MethodDef.builder("shout")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();

        IllegalArgumentException e = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> stringFunction().methodReference(ExpressionDef.constant("prefix_"), shout)
        );

        assertEquals("Static method java.lang.String#shout cannot be referenced on an instance", e.getMessage());
    }

    @Test
    public void theArityOfTheReferencedMethodIsValidated() {
        MethodDef twoArguments = MethodDef.builder("shout")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING)
            .addParameter("other", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();

        IllegalArgumentException e = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> stringFunction().staticMethodReference(OWNER, twoArguments)
        );

        assertEquals("Method reference test.Owner#shout accepts 2 argument(s) but "
            + "test.StringFunction#apply provides 1", e.getMessage());
    }

    @Test
    public void anAmbiguousMethodNameIsRejected() {
        IllegalArgumentException e = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> stringFunction().staticMethodReference(TypeDef.STRING, "valueOf")
        );

        Assertions.assertTrue(e.getMessage().startsWith("Ambiguous method reference: "), e.getMessage());
    }

    @Test
    public void theHelpersOnClassTypeDefAndExpressionDefRenderTheSameReferences() throws IOException {
        ClassTypeDef lambda = stringFunction();
        MethodDef shout = MethodDef.builder("shout")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();
        MethodDef constructor = MethodDef.constructor()
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.STRING)
            .build();
        ExpressionDef receiver = ExpressionDef.constant("prefix_");

        // MethodDef has no equals, so the references are compared by what they render to
        assertEquals(render(lambda.staticMethodReference(OWNER, shout)),
            render(lambda.staticMethodReference(OWNER, shout)));
        assertEquals(render(lambda.methodReference(receiver, "concat")),
            render(stringFunction().methodReference(receiver, TypeDef.STRING.findDeclaredMethods("concat", 1).get(0))));
        assertEquals(render(lambda.constructorReference(OWNER, constructor)),
            render(lambda.constructorReference(OWNER, constructor)));
        assertEquals(render(lambda.methodReference(receiver, "concat")),
            render(stringFunction().methodReference(receiver, "concat")));
    }

    @Test
    public void theReceiverMustBeOfAClassType() {
        IllegalArgumentException e = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> stringFunction().methodReference(ExpressionDef.constant(1), "toString")
        );

        assertEquals("The receiver of a method reference must be of a class type, but was: " + TypeDef.Primitive.INT,
            e.getMessage());
    }

    @Test
    public void referencesRenderAsArgumentsOfACall() throws IOException {
        ClassTypeDef functionType = ClassTypeDef.of("test.StringFunction");
        MethodDef run = MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("function", functionType)
            .returns(TypeDef.STRING)
            .build();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(run)
            .addMethod(MethodDef.builder("evaluate")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .build((aThis, params) -> StatementDef.multi(
                    aThis.invoke(run, List.of(stringFunction().staticMethodReference(OWNER, MethodDef.builder("shout")
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .addParameter("value", TypeDef.STRING)
                        .returns(TypeDef.STRING)
                        .build()))).returning()
                )))
            .build();

        assertEquals("""
package test;

import java.lang.String;

public class MyClass {
  public String run(StringFunction function) {
  }

  public String evaluate() {
    return this.run(Owner::shout);
  }
}
            """, writeClass(classDef));
    }

}
