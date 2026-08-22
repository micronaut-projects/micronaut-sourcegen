package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that generated class names that do not follow the JavaBeans naming conventions - most
 * notably the Micronaut {@code $Foo$Bar} convention - can be rendered.
 */
public class ClassNameWriteTest extends AbstractWriteTest {

    private static final String RESOLVER = "test.$Book$ELResolver";

    @Test
    public void writeDollarPrefixedClass() throws IOException {
        ClassDef classDef = ClassDef.builder(RESOLVER)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("name")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassTypeDef.STRING)
                .build((aThis, methodParameters) -> ExpressionDef.constant("book").returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.String;

public class $Book$ELResolver {
  public String name() {
    return "book";
  }
}
            """, data);

        JavaCompileAssertions.assertCompiles(data);
    }

    @Test
    public void dollarPrefixedClassAsFieldType() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Example")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("resolver", ClassTypeDef.of(RESOLVER))
                .addModifiers(Modifier.PUBLIC)
                .build())
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

public class Example {
  public $Book$ELResolver resolver;
}
            """, data);
    }

    @Test
    public void dollarPrefixedClassAsStaticFieldOwner() throws IOException {
        ClassTypeDef resolver = ClassTypeDef.of(RESOLVER);
        ClassDef classDef = ClassDef.builder("test.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("instance")
                .addModifiers(Modifier.PUBLIC)
                .returns(resolver)
                .build((aThis, methodParameters) -> resolver.getStaticField("INSTANCE", resolver).returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

public class Example {
  public $Book$ELResolver instance() {
    return $Book$ELResolver.INSTANCE;
  }
}
            """, data);
    }

    @Test
    public void dollarPrefixedClassAsAnnotationType() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Example")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of("test.$Generated$Marker")).build())
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

@$Generated$Marker
public class Example {
}
            """, data);
    }

    @Test
    public void dollarPrefixedClassAsClassLiteral() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("type")
                .addModifiers(Modifier.PUBLIC)
                .returns(Class.class)
                .build((aThis, methodParameters) -> new ExpressionDef.Constant(
                    ClassTypeDef.of(Class.class),
                    ClassTypeDef.of(RESOLVER)
                ).returning())
            )
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.lang.Class;

public class Example {
  public Class type() {
    return test.$Book$ELResolver.class;
  }
}
            """, data);
    }

    @Test
    public void innerTypeIsStillRenderedAsOuterDotInner() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Example")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("entry", ClassTypeDef.of(Map.Entry.class))
                .addModifiers(Modifier.PUBLIC)
                .build())
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

import java.util.Map;

public class Example {
  public Map.Entry entry;
}
            """, data);
    }

    @Test
    public void innerTypeOfADollarPrefixedOuterType() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Example")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("inner", new ClassTypeDef.ClassName("test.$Outer$Inner", true))
                .addModifiers(Modifier.PUBLIC)
                .build())
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

public class Example {
  public $Outer.Inner inner;
}
            """, data);
    }

    @Test
    public void defaultPackageClassAsFieldType() throws IOException {
        ClassDef classDef = ClassDef.builder("test.Example")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("value", ClassTypeDef.of("Foo"))
                .addModifiers(Modifier.PUBLIC)
                .build())
            .addField(FieldDef.builder("other", ClassTypeDef.of("bar"))
                .addModifiers(Modifier.PUBLIC)
                .build())
            .build();

        String data = writeClass(classDef);

        assertEquals("""
package test;

public class Example {
  public Foo value;

  public bar other;
}
            """, data);
    }

    @Test
    public void dollarPrefixedTypesCompileTogether() throws IOException {
        ClassTypeDef resolver = ClassTypeDef.of(RESOLVER);
        ClassDef resolverDef = ClassDef.builder(RESOLVER)
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("INSTANCE", resolver)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .initializer(resolver.instantiate())
                .build())
            .build();

        ClassDef userDef = ClassDef.builder("test.Example")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("resolver", resolver)
                .addModifiers(Modifier.PUBLIC)
                .build())
            .addMethod(MethodDef.builder("instance")
                .addModifiers(Modifier.PUBLIC)
                .returns(resolver)
                .build((aThis, methodParameters) -> resolver.getStaticField("INSTANCE", resolver).returning())
            )
            .addMethod(MethodDef.builder("type")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.of(Class.class))
                .build((aThis, methodParameters) -> new ExpressionDef.Constant(
                    ClassTypeDef.of(Class.class),
                    resolver
                ).returning())
            )
            .build();

        JavaCompileAssertions.assertCompiles(writeClass(resolverDef), writeClass(userDef));
    }

}
