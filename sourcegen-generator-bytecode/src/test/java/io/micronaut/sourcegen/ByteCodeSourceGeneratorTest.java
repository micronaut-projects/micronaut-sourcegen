package io.micronaut.sourcegen;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.lang.model.element.Modifier;
import java.io.PrintWriter;
import java.io.StringWriter;

class ByteCodeSourceGeneratorTest {

    @Test
    void testNullCondition() {
        ClassDef ifPredicateDef = ClassDef.builder("example.IfPredicate")
            .addMethod(MethodDef.builder("test").addParameter("param", TypeDef.OBJECT.makeNullable())
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(boolean.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    methodParameters.get(0).isNull().asConditionIf(TypeDef.Primitive.TRUE.returning()),
                    TypeDef.Primitive.FALSE.returning()
                )))
            .build();

        StringWriter stringWriter = new StringWriter();
        TraceClassVisitor tcv = new TraceClassVisitor(new PrintWriter(stringWriter));
        CheckClassAdapter cv = new CheckClassAdapter(tcv);

        new ByteCodeSourceGenerator().writeObject(cv, ifPredicateDef);

        tcv.visitEnd();

        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/IfPredicate
class example/IfPredicate {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN
    MAXSTACK = 2
    MAXLOCALS = 1

  // access flags 0x1
  public test(Ljava/lang/Object;)Z
    ALOAD 1
    IFNONNULL L0
    ICONST_1
    IRETURN
   L0
    ICONST_0
    IRETURN
    MAXSTACK = 100
    MAXLOCALS = 100
}
""", stringWriter.toString());
    }

    @Test
    void testBoxing() {
        ClassDef ifPredicateDef = ClassDef.builder("example.IfPredicate")
            .addMethod(MethodDef.builder("test").addParameter("param", TypeDef.of(Integer.class))
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(int.class)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning())
            )
            .build();

        StringWriter stringWriter = new StringWriter();
        TraceClassVisitor tcv = new TraceClassVisitor(new PrintWriter(stringWriter));
        CheckClassAdapter cv = new CheckClassAdapter(tcv);

        new ByteCodeSourceGenerator().writeObject(cv, ifPredicateDef);

        tcv.visitEnd();

        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/IfPredicate
class example/IfPredicate {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN
    MAXSTACK = 2
    MAXLOCALS = 1

  // access flags 0x1
  public test(Ljava/lang/Integer;)I
    ALOAD 1
    CHECKCAST java/lang/Number
    INVOKEVIRTUAL java/lang/Number.intValue ()I
    IRETURN
    MAXSTACK = 100
    MAXLOCALS = 100
}
""", stringWriter.toString());
    }

    @Test
    void testUnboxing() {
        ClassDef ifPredicateDef = ClassDef.builder("example.IfPredicate")
            .addMethod(MethodDef.builder("test").addParameter("param", int.class)
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(Integer.class)
                .build((aThis, methodParameters) -> methodParameters.get(0).returning())
            )
            .build();

        StringWriter stringWriter = new StringWriter();
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        TraceClassVisitor tcv = new TraceClassVisitor(classWriter, new PrintWriter(stringWriter));
        CheckClassAdapter cv = new CheckClassAdapter(tcv);

        new ByteCodeSourceGenerator().writeObject(cv, ifPredicateDef);

        tcv.visitEnd();

        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/IfPredicate
class example/IfPredicate {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN
    MAXSTACK = 2
    MAXLOCALS = 1

  // access flags 0x1
  public test(I)Ljava/lang/Integer;
    ILOAD 1
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    ARETURN
    MAXSTACK = 100
    MAXLOCALS = 100
}
""", stringWriter.toString());
    }

    @Test
    void testEnum() {
        EnumDef myEnum = EnumDef.builder("MyEnum").addEnumConstant("A").addEnumConstant("B").addEnumConstant("C").build();

        StringWriter stringWriter = new StringWriter();
        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        TraceClassVisitor tcv = new TraceClassVisitor(classWriter, new PrintWriter(stringWriter));
        CheckClassAdapter cv = new CheckClassAdapter(tcv);

        new ByteCodeSourceGenerator().writeObject(cv, myEnum);

        tcv.visitEnd();

        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x4010
// signature Ljava/lang/Enum<LMyEnum;>;
// declaration: MyEnum extends java.lang.Enum<MyEnum>
final enum MyEnum extends java/lang/Enum {


  // access flags 0x4019
  public final static enum LMyEnum; A

  // access flags 0x4019
  public final static enum LMyEnum; B

  // access flags 0x4019
  public final static enum LMyEnum; C

  // access flags 0x0
  <init>(Ljava/lang/String;I)V
    ALOAD 0
    ALOAD 1
    ILOAD 2
    INVOKESPECIAL java/lang/Enum.<init> (Ljava/lang/String;I)V
    RETURN
    MAXSTACK = 100
    MAXLOCALS = 100

  // access flags 0xA
  private static [LMyEnum; $VALUES

  // access flags 0x9
  public static values()[LMyEnum;
    GETSTATIC MyEnum.$VALUES : [LMyEnum;
    INVOKEVIRTUAL [LMyEnum;.clone ()Ljava/lang/Object;
    CHECKCAST [LMyEnum;
    ARETURN
    MAXSTACK = 100
    MAXLOCALS = 100

  // access flags 0x9
  public static valueOf(Ljava/lang/String;)LMyEnum;
    LDC LMyEnum;.class
    ALOAD 0
    INVOKESTATIC java/lang/Enum.valueOf (Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;
    CHECKCAST MyEnum
    ARETURN
    MAXSTACK = 100
    MAXLOCALS = 100

  // access flags 0xA
  private static $values()[LMyEnum;
    ICONST_3
    ANEWARRAY MyEnum
    DUP
    ICONST_0
    GETSTATIC MyEnum.A : LMyEnum;
    AASTORE
    DUP
    ICONST_1
    GETSTATIC MyEnum.B : LMyEnum;
    AASTORE
    DUP
    ICONST_2
    GETSTATIC MyEnum.C : LMyEnum;
    AASTORE
    ARETURN
    MAXSTACK = 100
    MAXLOCALS = 100

  // access flags 0x8
  static <clinit>()V
    NEW MyEnum
    DUP
    LDC "A"
    ICONST_0
    INVOKESPECIAL MyEnum.<init> (Ljava/lang/String;I)V
    PUTSTATIC MyEnum.A : LMyEnum;
    NEW MyEnum
    DUP
    LDC "B"
    ICONST_1
    INVOKESPECIAL MyEnum.<init> (Ljava/lang/String;I)V
    PUTSTATIC MyEnum.B : LMyEnum;
    NEW MyEnum
    DUP
    LDC "C"
    ICONST_2
    INVOKESPECIAL MyEnum.<init> (Ljava/lang/String;I)V
    PUTSTATIC MyEnum.C : LMyEnum;
    INVOKESTATIC MyEnum.$values ()[LMyEnum;
    PUTSTATIC MyEnum.$VALUES : [LMyEnum;
    RETURN
    MAXSTACK = 100
    MAXLOCALS = 100
}
""", stringWriter.toString());
    }

}
