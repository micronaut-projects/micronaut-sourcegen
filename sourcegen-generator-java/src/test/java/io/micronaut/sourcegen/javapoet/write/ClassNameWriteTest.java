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

import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.compile;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.run;
import static io.micronaut.sourcegen.javapoet.write.JavaCompileAssertions.stringMethod;
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

    /**
     * A nested class whose own simple name has a {@code $}: the bytecode writer names it by its binary name, the
     * source declares {@code Impl} and refers to {@code DollarNested.Inner.Impl}.
     */
    @Test
    void nestedClassWithADollarInItsSimpleName() throws Exception {
        var outerName = "test.DollarNested";
        var nestedType = new ClassTypeDef.ClassName(outerName + "$Inner$Impl", true);
        var def = ClassDef.builder(outerName).addModifiers(Modifier.PUBLIC)
            .addInnerType(ClassDef.builder("test.Inner$Impl").addModifiers(Modifier.PUBLIC, Modifier.STATIC).build())
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((self, p) -> nestedType.instantiate().returning()))
            .build();
        assertEquals(outerName + "$Inner$Impl", run(def).getClass().getName());
    }

    /**
     * A generated class named {@code String} that returns a {@code java.lang.String}.
     */
    @Test
    void generatedClassNamedString() throws Exception {
        var def = stringMethod("test.String", ExpressionDef.constant("ok").returning());
        assertEquals("ok", run(def));
    }

    /**
     * A nested class named {@code String} beside a method returning a {@code java.lang.String}.
     */
    @Test
    void nestedClassNamedString() throws Exception {
        var inner = ClassDef.builder("test.String").addModifiers(Modifier.PUBLIC, Modifier.STATIC).build();
        var def = ClassDef.builder("test.NestedString").addModifiers(Modifier.PUBLIC).addInnerType(inner)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("ok").returning()))
            .build();
        assertEquals("ok", run(def));
    }

    /**
     * A type variable named {@code String} beside a method returning a {@code java.lang.String}.
     */
    @Test
    void typeVariableNamedLikeAReferencedType() throws Exception {
        var def = ClassDef.builder("test.ShadowingVariable").addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("String"))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(String.class)
                .build((self, p) -> ExpressionDef.constant("ok").returning()))
            .build();
        assertEquals("ok", run(def));
    }

    /**
     * An imported type whose simple name is a member type the class inherits: {@code State} in a subclass of
     * {@code Thread} is {@code Thread.State}, not the imported {@code test.other.State}.
     */
    @Test
    void importedTypeNamedLikeAnInheritedMemberType() throws Exception {
        var state = ClassDef.builder("test.other.State").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).build()).build();
        var def = ClassDef.builder("test.Worker").addModifiers(Modifier.PUBLIC).superclass(ClassTypeDef.of(Thread.class))
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((self, p) -> state.asTypeDef().instantiate().returning()))
            .build();
        try (var loader = compile(state, def)) {
            var cls = loader.loadClass(def.getName());
            var result = cls.getMethod("call").invoke(cls.getConstructor().newInstance());
            assertEquals("test.other.State", result.getClass().getName());
        }
    }

    /**
     * A generated class in a package with an upper case segment, which the name guessing cannot split.
     */
    @Test
    void generatedClassInAPackageWithAnUpperCaseSegment() throws Exception {
        var target = ClassDef.builder("test.Upper.Target").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.constructor().addModifiers(Modifier.PUBLIC).build()).build();
        var def = ClassDef.builder("test.UpperCaller").addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("call").addModifiers(Modifier.PUBLIC).returns(Object.class)
                .build((self, p) -> target.asTypeDef().instantiate().returning()))
            .build();
        try (var loader = compile(target, def)) {
            var cls = loader.loadClass(def.getName());
            assertEquals("test.Upper.Target", cls.getMethod("call").invoke(cls.getConstructor().newInstance()).getClass().getName());
        }
    }

}
