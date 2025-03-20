package io.micronaut.sourcegen.bytecode;

import io.micronaut.context.BeanResolutionContext;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.custom.visitor.GenerateLambdaVisitor;
import io.micronaut.sourcegen.custom.visitor.innerTypes.GenerateInnerTypeInEnumVisitor;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.JavaIdioms;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.sourcegen.model.VariableDef;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.micronaut.sourcegen.bytecode.DecompilerUtils.decompileToJava;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.EQUAL_TO;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.GREATER_THAN;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.GREATER_THAN_OR_EQUAL;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.LESS_THAN;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.LESS_THAN_OR_EQUAL;
import static io.micronaut.sourcegen.model.ExpressionDef.ComparisonOperation.OpType.NOT_EQUAL_TO;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.ADDITION;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_AND;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_LEFT_SHIFT;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_OR;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_RIGHT_SHIFT;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_UNSIGNED_RIGHT_SHIFT;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.BITWISE_XOR;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.DIVISION;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.MODULUS;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.MULTIPLICATION;
import static io.micronaut.sourcegen.model.ExpressionDef.MathBinaryOperation.OpType.SUBTRACTION;
import static io.micronaut.sourcegen.model.ExpressionDef.MathUnaryOperation.OpType.NEGATE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteCodeWriterTest {

    @Test
    void callTypeVariable() {
        ClassDef predicateGeneric2 = ClassDef.builder("test.IfPredicateGeneric")
            .addSuperinterface(TypeDef.of(Predicate.class))
            .addMethod(MethodDef.builder("test")
                .addParameters(TypeDef.variable("T", TypeDef.OBJECT))
                .returns(boolean.class)
                .build((aThis, methodParameters) ->
                    methodParameters.get(0).equalsStructurally(ExpressionDef.constant(Integer.valueOf(1))).returning())
            )
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(predicateGeneric2, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;Ljava/util/function/Predicate;
// declaration: test/IfPredicateGeneric implements java.util.function.Predicate
class test/IfPredicateGeneric implements java/util/function/Predicate {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  // signature (Ljava/lang/Object;)Z
  // declaration: boolean test(java.lang.Object)
  test(Ljava/lang/Object;)Z
   L0
    ALOAD 1
    ICONST_1
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    INVOKEVIRTUAL java/lang/Object.equals (Ljava/lang/Object;)Z
    ICONST_1
    IF_ICMPNE L1
    ICONST_1
    GOTO L2
   L1
    ICONST_0
   L2
    IRETURN
   L3
    LOCALVARIABLE arg1 Ljava/lang/Object; L0 L3 1
}
""", bytecode);

        Assertions.assertEquals("""
package test;

import java.util.function.Predicate;

class IfPredicateGeneric implements Predicate {
   boolean test(Object arg1) {
      return arg1.equals(1);
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void lambda() {
        ClassDef classDef = GenerateLambdaVisitor.getSpec("example").theClass();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/MyClassWithLambda
public class example/MyClassWithLambda {


  // access flags 0x0
  Ljava/lang/String; name

  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  // signature (Ljava/util/function/Function<Ljava/lang/String;Ljava/lang/String;>;Ljava/lang/String;)Ljava/lang/String;
  // declaration: java.lang.String methodInvoker(java.util.function.Function<java.lang.String, java.lang.String>, java.lang.String)
  methodInvoker(Ljava/util/function/Function;Ljava/lang/String;)Ljava/lang/String;
   L0
    ALOAD 1
    ALOAD 2
    INVOKEINTERFACE java/util/function/Function.apply (Ljava/lang/Object;)Ljava/lang/Object; (itf)
    CHECKCAST java/lang/String
    ARETURN
   L1
    LOCALVARIABLE arg1 Ljava/util/function/Function; L0 L1 1
    LOCALVARIABLE arg2 Ljava/lang/String; L0 L1 2

  // access flags 0x1
  public toString()Ljava/lang/String;
    LDC "MyClass"
    ARETURN

  // access flags 0x1
  public callLambda(Ljava/lang/String;)Ljava/lang/String;
   L0
   L1
    INVOKEDYNAMIC apply()Lexample/StringFunction; [
      // handle kind 0x6 : INVOKESTATIC
      java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
      // arguments:
      (Ljava/lang/String;)Ljava/lang/String;,\s
      // handle kind 0x6 : INVOKESTATIC
      example/MyClassWithLambda.lambda$callLambda$0(Ljava/lang/String;)Ljava/lang/String;,\s
      (Ljava/lang/String;)Ljava/lang/String;
    ]
    ASTORE 2
    ALOAD 2
    ALOAD 1
    INVOKEINTERFACE example/StringFunction.apply (Ljava/lang/String;)Ljava/lang/String; (itf)
    ARETURN
   L2
    LOCALVARIABLE input Ljava/lang/String; L0 L2 1
    LOCALVARIABLE function Lexample/StringFunction; L1 L2 2

  // access flags 0xA
  private static lambda$callLambda$0(Ljava/lang/String;)Ljava/lang/String;
   L0
    ALOAD 0
    ICONST_1
    INVOKEVIRTUAL java/lang/String.substring (I)Ljava/lang/String;
    ARETURN
   L1
    LOCALVARIABLE arg1 Ljava/lang/String; L0 L1 1

  // access flags 0x1
  public callStatefulLambda(Ljava/lang/String;)Ljava/lang/String;
   L0
   L1
    LDC "prefix_"
    ASTORE 2
   L2
    ALOAD 2
    ALOAD 0
    INVOKEDYNAMIC apply(Ljava/lang/String;Lexample/MyClassWithLambda;)Lexample/StringFunction; [
      // handle kind 0x6 : INVOKESTATIC
      java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
      // arguments:
      (Ljava/lang/String;)Ljava/lang/String;,\s
      // handle kind 0x6 : INVOKESTATIC
      example/MyClassWithLambda.lambda$callStatefulLambda$0(Ljava/lang/String;Lexample/MyClassWithLambda;Ljava/lang/String;)Ljava/lang/String;,\s
      (Ljava/lang/String;)Ljava/lang/String;
    ]
    ASTORE 3
    ALOAD 3
    ALOAD 1
    INVOKEINTERFACE example/StringFunction.apply (Ljava/lang/String;)Ljava/lang/String; (itf)
    ARETURN
   L3
    LOCALVARIABLE input Ljava/lang/String; L0 L3 1
    LOCALVARIABLE constant Ljava/lang/String; L1 L3 2
    LOCALVARIABLE function Lexample/StringFunction; L2 L3 3

  // access flags 0xA
  private static lambda$callStatefulLambda$0(Ljava/lang/String;Lexample/MyClassWithLambda;Ljava/lang/String;)Ljava/lang/String;
   L0
    NEW java/lang/StringBuilder
    DUP
    ALOAD 0
    INVOKESPECIAL java/lang/StringBuilder.<init> (Ljava/lang/String;)V
    ALOAD 2
    ICONST_1
    INVOKEVIRTUAL java/lang/String.substring (I)Ljava/lang/String;
    INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;
    ALOAD 1
    INVOKEVIRTUAL example/MyClassWithLambda.toString ()Ljava/lang/String;
    INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;
    ALOAD 1
    GETFIELD example/MyClassWithLambda.name : Ljava/lang/String;
    INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/Object;)Ljava/lang/StringBuilder;
    INVOKEVIRTUAL java/lang/StringBuilder.toString ()Ljava/lang/String;
    ARETURN
   L1
    LOCALVARIABLE constant Ljava/lang/String; L0 L1 1
    LOCALVARIABLE this Lexample/MyClassWithLambda; L0 L1 2
    LOCALVARIABLE arg1 Ljava/lang/String; L0 L1 3

  // access flags 0x1
  public callGenericLambda(Ljava/lang/String;)Ljava/lang/String;
   L0
   L1
    LDC "prefix_"
    ASTORE 2
   L2
    ALOAD 2
    INVOKEDYNAMIC apply(Ljava/lang/String;)Ljava/util/function/Function; [
      // handle kind 0x6 : INVOKESTATIC
      java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
      // arguments:
      (Ljava/lang/Object;)Ljava/lang/Object;,\s
      // handle kind 0x6 : INVOKESTATIC
      example/MyClassWithLambda.lambda$callGenericLambda$0(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;,\s
      (Ljava/lang/String;)Ljava/lang/String;
    ]
    ASTORE 3
    ALOAD 3
    ALOAD 1
    INVOKEINTERFACE java/util/function/Function.apply (Ljava/lang/Object;)Ljava/lang/Object; (itf)
    CHECKCAST java/lang/String
    ARETURN
   L3
    LOCALVARIABLE input Ljava/lang/String; L0 L3 1
    LOCALVARIABLE constant Ljava/lang/String; L1 L3 2
    LOCALVARIABLE function Ljava/util/function/Function; L2 L3 3

  // access flags 0xA
  private static lambda$callGenericLambda$0(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
   L0
    NEW java/lang/StringBuilder
    DUP
    ALOAD 0
    INVOKESPECIAL java/lang/StringBuilder.<init> (Ljava/lang/String;)V
    ALOAD 1
    ICONST_1
    INVOKEVIRTUAL java/lang/String.substring (I)Ljava/lang/String;
    INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;
    INVOKEVIRTUAL java/lang/StringBuilder.toString ()Ljava/lang/String;
    ARETURN
   L1
    LOCALVARIABLE constant Ljava/lang/String; L0 L1 1
    LOCALVARIABLE arg1 Ljava/lang/String; L0 L1 2

  // access flags 0x1
  public callGenericLambda2(Ljava/lang/String;)Ljava/lang/String;
   L0
   L1
    LDC "prefix_"
    ASTORE 2
    ALOAD 0
    ALOAD 2
    INVOKEDYNAMIC apply(Ljava/lang/String;)Ljava/util/function/Function; [
      // handle kind 0x6 : INVOKESTATIC
      java/lang/invoke/LambdaMetafactory.metafactory(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;
      // arguments:
      (Ljava/lang/Object;)Ljava/lang/Object;,\s
      // handle kind 0x6 : INVOKESTATIC
      example/MyClassWithLambda.lambda$callGenericLambda2$0(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;,\s
      (Ljava/lang/String;)Ljava/lang/String;
    ]
    ALOAD 1
    INVOKEVIRTUAL example/MyClassWithLambda.methodInvoker (Ljava/util/function/Function;Ljava/lang/String;)Ljava/lang/String;
    ARETURN
   L2
    LOCALVARIABLE input Ljava/lang/String; L0 L2 1
    LOCALVARIABLE constant Ljava/lang/String; L1 L2 2

  // access flags 0xA
  private static lambda$callGenericLambda2$0(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
   L0
    NEW java/lang/StringBuilder
    DUP
    ALOAD 0
    INVOKESPECIAL java/lang/StringBuilder.<init> (Ljava/lang/String;)V
    ALOAD 1
    ICONST_1
    INVOKEVIRTUAL java/lang/String.substring (I)Ljava/lang/String;
    INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;
    INVOKEVIRTUAL java/lang/StringBuilder.toString ()Ljava/lang/String;
    ARETURN
   L1
    LOCALVARIABLE constant Ljava/lang/String; L0 L1 1
    LOCALVARIABLE arg1 Ljava/lang/String; L0 L1 2
}
""", bytecode);

        Assertions.assertEquals("""
package example;

import java.util.function.Function;

public class MyClassWithLambda {
   String name;

   String methodInvoker(Function arg1, String arg2) {
      return (String)arg1.apply(arg2);
   }

   public String toString() {
      return "MyClass";
   }

   public String callLambda(String input) {
      StringFunction function = MyClassWithLambda::lambda$callLambda$0;
      return function.apply(input);
   }

   private static String lambda$callLambda$0(String var0) {
      return var0.substring(1);
   }

   public String callStatefulLambda(String input) {
      String constant = "prefix_";
      StringFunction function = MyClassWithLambda::lambda$callStatefulLambda$0;
      return function.apply(input);
   }

   private static String lambda$callStatefulLambda$0(String var0, MyClassWithLambda constant, String var2) {
      return var0 + ((String)var2).substring(1) + ((MyClassWithLambda)constant).toString() + constant.name;
   }

   public String callGenericLambda(String input) {
      String constant = "prefix_";
      Function function = MyClassWithLambda::lambda$callGenericLambda$0;
      return (String)function.apply(input);
   }

   private static String lambda$callGenericLambda$0(String var0, String constant) {
      return var0 + constant.substring(1);
   }

   public String callGenericLambda2(String input) {
      String constant = "prefix_";
      return this.methodInvoker(MyClassWithLambda::lambda$callGenericLambda2$0, input);
   }

   private static String lambda$callGenericLambda2$0(String var0, String constant) {
      return var0 + constant.substring(1);
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void voidClassLoading() {
        final Constructor<?> CONSTRUCTOR_CLASS_VALUE = ReflectionUtils.getRequiredInternalConstructor(
            AnnotationClassValue.class,
            String.class
        );

        final Constructor<?> CONSTRUCTOR_CLASS_VALUE_WITH_CLASS = ReflectionUtils.getRequiredInternalConstructor(
            AnnotationClassValue.class,
            Class.class
        );
        ClassTypeDef TYPE_ANNOTATION_CLASS_VALUE = ClassTypeDef.of(AnnotationClassValue.class);
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(
                MethodDef.builder("loadClass")
                    .addModifiers(Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC)
                    .returns(TYPE_ANNOTATION_CLASS_VALUE)
                    .buildStatic(methodParameters -> StatementDef.doTry(
                        TYPE_ANNOTATION_CLASS_VALUE.instantiate(
                            CONSTRUCTOR_CLASS_VALUE_WITH_CLASS,
                            ExpressionDef.constant(TypeDef.VOID)
                        ).returning()
                    ).doCatch(Throwable.class, exceptionVar -> TYPE_ANNOTATION_CLASS_VALUE.instantiate(
                        CONSTRUCTOR_CLASS_VALUE,
                        ExpressionDef.constant(TypeDef.VOID.name())
                    ).returning()))
            )
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1A
  private final static loadClass()Lio/micronaut/core/annotation/AnnotationClassValue;
    TRYCATCHBLOCK L0 L1 L2 java/lang/Throwable
   L0
    NEW io/micronaut/core/annotation/AnnotationClassValue
    DUP
    GETSTATIC java/lang/Void.TYPE : Ljava/lang/Class;
    INVOKESPECIAL io/micronaut/core/annotation/AnnotationClassValue.<init> (Ljava/lang/Class;)V
    ARETURN
   L1
    GOTO L3
   L2
    ASTORE 0
    NEW io/micronaut/core/annotation/AnnotationClassValue
    DUP
    LDC "void"
    INVOKESPECIAL io/micronaut/core/annotation/AnnotationClassValue.<init> (Ljava/lang/String;)V
    ARETURN
    GOTO L3
   L3
}
""", bytecode);

        Assertions.assertEquals("""
package example;

import io.micronaut.core.annotation.AnnotationClassValue;

public class Example {
   private static final AnnotationClassValue loadClass() {
      try {
         return new AnnotationClassValue(Void.TYPE);
      } catch (Throwable var1) {
         return new AnnotationClassValue("void");
      }
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void ifElseExpression() {

        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(boolean.class)
                .addParameters(int.class)
                .addParameters(String.class)
                .build((aThis, methodParameters) ->
                    methodParameters.get(0)
                        .isTrue()
                        .doIfElse(methodParameters.get(1), methodParameters.get(2))
                        .returning())
            )
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(ZILjava/lang/String;)Ljava/lang/Object;
   L0
    ILOAD 1
    ICONST_1
    IF_ICMPNE L1
    ILOAD 2
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    GOTO L2
   L1
    ALOAD 3
   L2
    ARETURN
   L3
    LOCALVARIABLE arg1 Z L0 L3 1
    LOCALVARIABLE arg2 I L0 L3 2
    LOCALVARIABLE arg3 Ljava/lang/String; L0 L3 3
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   Object myMethod(boolean arg1, int arg2, String arg3) {
      return arg1 ? arg2 : arg3;
   }
}
""", decompileToJava(bytes));
    }


    @Test
    void compareOpsCast() {

        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(int.class)
                .addParameters(double.class)
                .build((aThis, methodParameters) ->
                    TypeDef.OBJECT.array().instantiate(
                        methodParameters.get(0).compare(EQUAL_TO, methodParameters.get(1)),
                        methodParameters.get(0).compare(NOT_EQUAL_TO, methodParameters.get(1)),
                        methodParameters.get(0).compare(GREATER_THAN, methodParameters.get(1)),
                        methodParameters.get(0).compare(LESS_THAN, methodParameters.get(1)),
                        methodParameters.get(0).compare(GREATER_THAN_OR_EQUAL, methodParameters.get(1)),
                        methodParameters.get(0).compare(LESS_THAN_OR_EQUAL, methodParameters.get(1))
                    ).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(ID)[Ljava/lang/Object;
   L0
    BIPUSH 6
    ANEWARRAY java/lang/Object
    DUP
    ICONST_0
    ILOAD 1
    DLOAD 2
    D2I
    IF_ICMPNE L1
    ICONST_1
    GOTO L2
   L1
    ICONST_0
   L2
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_1
    ILOAD 1
    DLOAD 2
    D2I
    IF_ICMPEQ L3
    ICONST_1
    GOTO L4
   L3
    ICONST_0
   L4
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_2
    ILOAD 1
    DLOAD 2
    D2I
    IF_ICMPLE L5
    ICONST_1
    GOTO L6
   L5
    ICONST_0
   L6
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_3
    ILOAD 1
    DLOAD 2
    D2I
    IF_ICMPGE L7
    ICONST_1
    GOTO L8
   L7
    ICONST_0
   L8
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_4
    ILOAD 1
    DLOAD 2
    D2I
    IF_ICMPLT L9
    ICONST_1
    GOTO L10
   L9
    ICONST_0
   L10
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_5
    ILOAD 1
    DLOAD 2
    D2I
    IF_ICMPGT L11
    ICONST_1
    GOTO L12
   L11
    ICONST_0
   L12
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    ARETURN
   L13
    LOCALVARIABLE arg1 I L0 L13 1
    LOCALVARIABLE arg2 D L0 L13 2
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   Object[] myMethod(int arg1, double arg2) {
      return new Object[]{arg1 == (int)arg2, arg1 != (int)arg2, arg1 > (int)arg2, arg1 < (int)arg2, arg1 >= (int)arg2, arg1 <= (int)arg2};
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void compareOps() {

        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(int.class)
                .addParameters(int.class)
                .build((aThis, methodParameters) ->
                    TypeDef.OBJECT.array().instantiate(
                        methodParameters.get(0).compare(EQUAL_TO, methodParameters.get(1)),
                        methodParameters.get(0).compare(NOT_EQUAL_TO, methodParameters.get(1)),
                        methodParameters.get(0).compare(GREATER_THAN, methodParameters.get(1)),
                        methodParameters.get(0).compare(LESS_THAN, methodParameters.get(1)),
                        methodParameters.get(0).compare(GREATER_THAN_OR_EQUAL, methodParameters.get(1)),
                        methodParameters.get(0).compare(LESS_THAN_OR_EQUAL, methodParameters.get(1))
                    ).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(II)[Ljava/lang/Object;
   L0
    BIPUSH 6
    ANEWARRAY java/lang/Object
    DUP
    ICONST_0
    ILOAD 1
    ILOAD 2
    IF_ICMPNE L1
    ICONST_1
    GOTO L2
   L1
    ICONST_0
   L2
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_1
    ILOAD 1
    ILOAD 2
    IF_ICMPEQ L3
    ICONST_1
    GOTO L4
   L3
    ICONST_0
   L4
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_2
    ILOAD 1
    ILOAD 2
    IF_ICMPLE L5
    ICONST_1
    GOTO L6
   L5
    ICONST_0
   L6
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_3
    ILOAD 1
    ILOAD 2
    IF_ICMPGE L7
    ICONST_1
    GOTO L8
   L7
    ICONST_0
   L8
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_4
    ILOAD 1
    ILOAD 2
    IF_ICMPLT L9
    ICONST_1
    GOTO L10
   L9
    ICONST_0
   L10
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_5
    ILOAD 1
    ILOAD 2
    IF_ICMPGT L11
    ICONST_1
    GOTO L12
   L11
    ICONST_0
   L12
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    ARETURN
   L13
    LOCALVARIABLE arg1 I L0 L13 1
    LOCALVARIABLE arg2 I L0 L13 2
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   Object[] myMethod(int arg1, int arg2) {
      return new Object[]{arg1 == arg2, arg1 != arg2, arg1 > arg2, arg1 < arg2, arg1 >= arg2, arg1 <= arg2};
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void mathOpsCast() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(int.class)
                .addParameters(float.class)
                .build((aThis, methodParameters) ->
                    TypeDef.OBJECT.array().instantiate(
                        methodParameters.get(0).math(ADDITION, methodParameters.get(1)),
                        methodParameters.get(0).math(SUBTRACTION, methodParameters.get(1)),
                        methodParameters.get(0).math(MULTIPLICATION, methodParameters.get(1)),
                        methodParameters.get(0).math(DIVISION, methodParameters.get(1)),
                        methodParameters.get(0).math(MODULUS, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_AND, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_OR, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_XOR, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_LEFT_SHIFT, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_RIGHT_SHIFT, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_UNSIGNED_RIGHT_SHIFT, methodParameters.get(1))
                    ).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(IF)[Ljava/lang/Object;
   L0
    BIPUSH 11
    ANEWARRAY java/lang/Object
    DUP
    ICONST_0
    ILOAD 1
    FLOAD 2
    F2I
    IADD
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_1
    ILOAD 1
    FLOAD 2
    F2I
    ISUB
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_2
    ILOAD 1
    FLOAD 2
    F2I
    IMUL
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_3
    ILOAD 1
    FLOAD 2
    F2I
    IDIV
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_4
    ILOAD 1
    FLOAD 2
    F2I
    IREM
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_5
    ILOAD 1
    FLOAD 2
    F2I
    IAND
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 6
    ILOAD 1
    FLOAD 2
    F2I
    IOR
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 7
    ILOAD 1
    FLOAD 2
    F2I
    IXOR
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 8
    ILOAD 1
    FLOAD 2
    F2I
    ISHL
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 9
    ILOAD 1
    FLOAD 2
    F2I
    ISHR
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 10
    ILOAD 1
    FLOAD 2
    F2I
    IUSHR
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    ARETURN
   L1
    LOCALVARIABLE arg1 I L0 L1 1
    LOCALVARIABLE arg2 F L0 L1 2
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   Object[] myMethod(int arg1, float arg2) {
      return new Object[]{arg1 + (int)arg2, arg1 - (int)arg2, arg1 * (int)arg2, arg1 / (int)arg2, arg1 % (int)arg2, arg1 & (int)arg2, arg1 | (int)arg2, arg1 ^ (int)arg2, arg1 << (int)arg2, arg1 >> (int)arg2, arg1 >>> (int)arg2};
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void mathOpsCast2() {
        final ClassTypeDef MATH_TYPE = ClassTypeDef.of(Math.class);
        java.lang.reflect.Method POW_METHOD = ReflectionUtils.getRequiredMethod(Math.class, "pow", double.class, double.class);

        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(double.class)
                .addParameters(double.class)
                .returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) ->
                    MATH_TYPE.invokeStatic(POW_METHOD, methodParameters.get(0), methodParameters.get(1))
                        .cast(TypeDef.Primitive.LONG)
                        .returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(DD)Ljava/lang/Object;
   L0
    DLOAD 1
    DLOAD 3
    INVOKESTATIC java/lang/Math.pow (DD)D
    D2L
    INVOKESTATIC java/lang/Long.valueOf (J)Ljava/lang/Long;
    ARETURN
   L1
    LOCALVARIABLE arg1 D L0 L1 1
    LOCALVARIABLE arg2 D L0 L1 2
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   Object myMethod(double arg1, double var3) {
      return (long)Math.pow(arg1, var3);
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void mathOps() {

        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(int.class)
                .addParameters(int.class)
                .build((aThis, methodParameters) ->
                    TypeDef.OBJECT.array().instantiate(
                        methodParameters.get(0).math(ADDITION, methodParameters.get(1)),
                        methodParameters.get(0).math(SUBTRACTION, methodParameters.get(1)),
                        methodParameters.get(0).math(MULTIPLICATION, methodParameters.get(1)),
                        methodParameters.get(0).math(DIVISION, methodParameters.get(1)),
                        methodParameters.get(0).math(MODULUS, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_AND, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_OR, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_XOR, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_LEFT_SHIFT, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_RIGHT_SHIFT, methodParameters.get(1)),
                        methodParameters.get(0).math(BITWISE_UNSIGNED_RIGHT_SHIFT, methodParameters.get(1))
                    ).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(II)[Ljava/lang/Object;
   L0
    BIPUSH 11
    ANEWARRAY java/lang/Object
    DUP
    ICONST_0
    ILOAD 1
    ILOAD 2
    IADD
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_1
    ILOAD 1
    ILOAD 2
    ISUB
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_2
    ILOAD 1
    ILOAD 2
    IMUL
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_3
    ILOAD 1
    ILOAD 2
    IDIV
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_4
    ILOAD 1
    ILOAD 2
    IREM
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_5
    ILOAD 1
    ILOAD 2
    IAND
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 6
    ILOAD 1
    ILOAD 2
    IOR
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 7
    ILOAD 1
    ILOAD 2
    IXOR
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 8
    ILOAD 1
    ILOAD 2
    ISHL
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 9
    ILOAD 1
    ILOAD 2
    ISHR
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    BIPUSH 10
    ILOAD 1
    ILOAD 2
    IUSHR
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    ARETURN
   L1
    LOCALVARIABLE arg1 I L0 L1 1
    LOCALVARIABLE arg2 I L0 L1 2
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   Object[] myMethod(int arg1, int arg2) {
      return new Object[]{arg1 + arg2, arg1 - arg2, arg1 * arg2, arg1 / arg2, arg1 % arg2, arg1 & arg2, arg1 | arg2, arg1 ^ arg2, arg1 << arg2, arg1 >> arg2, arg1 >>> arg2};
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void mathOpsUnary() {

        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(int.class)
                .build((aThis, methodParameters) ->
                    TypeDef.OBJECT.array().instantiate(
                        methodParameters.get(0).math(NEGATE)
                    ).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(I)[Ljava/lang/Object;
   L0
    ICONST_1
    ANEWARRAY java/lang/Object
    DUP
    ICONST_0
    ILOAD 1
    INEG
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    ARETURN
   L1
    LOCALVARIABLE arg1 I L0 L1 1
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   Object[] myMethod(int arg1) {
      return new Object[]{-arg1};
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void equalsAndNotEquals() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(Object.class)
                .addParameters(Object.class)
                .build((aThis, methodParameters) ->
                    TypeDef.OBJECT.array().instantiate(
                        methodParameters.get(0).equalsStructurally(methodParameters.get(1)),
                        methodParameters.get(0).notEqualsStructurally(methodParameters.get(1)),
                        methodParameters.get(0).equalsReferentially(methodParameters.get(1)),
                        methodParameters.get(0).notEqualsReferentially(methodParameters.get(1))
                    ).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(Ljava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;
   L0
    ICONST_4
    ANEWARRAY java/lang/Object
    DUP
    ICONST_0
    ALOAD 1
    ALOAD 2
    INVOKEVIRTUAL java/lang/Object.equals (Ljava/lang/Object;)Z
    ICONST_1
    IF_ICMPNE L1
    ICONST_1
    GOTO L2
   L1
    ICONST_0
   L2
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_1
    ALOAD 1
    ALOAD 2
    INVOKEVIRTUAL java/lang/Object.equals (Ljava/lang/Object;)Z
    ICONST_1
    IF_ICMPEQ L3
    ICONST_1
    GOTO L4
   L3
    ICONST_0
   L4
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_2
    ALOAD 1
    ALOAD 2
    IF_ACMPNE L5
    ICONST_1
    GOTO L6
   L5
    ICONST_0
   L6
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    DUP
    ICONST_3
    ALOAD 1
    ALOAD 2
    IF_ACMPEQ L7
    ICONST_1
    GOTO L8
   L7
    ICONST_0
   L8
    INVOKESTATIC java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
    AASTORE
    ARETURN
   L9
    LOCALVARIABLE arg1 Ljava/lang/Object; L0 L9 1
    LOCALVARIABLE arg2 Ljava/lang/Object; L0 L9 2
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   Object[] myMethod(Object arg1, Object arg2) {
      return new Object[]{arg1.equals(arg2), !arg1.equals(arg2), arg1 == arg2, arg1 != arg2};
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void math() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(int.class)
                .addParameters(int.class)
                .addParameters(float.class)
                .addParameters(float.class)
                .addParameters(double.class)
                .addParameters(double.class)
                .build((aThis, methodParameters) ->
                    TypeDef.OBJECT.array().instantiate(
                        methodParameters.get(0).math(ADDITION, methodParameters.get(1)),
                        methodParameters.get(2).math(ADDITION, methodParameters.get(3)),
                        methodParameters.get(4).math(ADDITION, methodParameters.get(5))
                    ).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(IIFFDD)[Ljava/lang/Object;
   L0
    ICONST_3
    ANEWARRAY java/lang/Object
    DUP
    ICONST_0
    ILOAD 1
    ILOAD 2
    IADD
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    AASTORE
    DUP
    ICONST_1
    FLOAD 3
    FLOAD 4
    FADD
    INVOKESTATIC java/lang/Float.valueOf (F)Ljava/lang/Float;
    AASTORE
    DUP
    ICONST_2
    DLOAD 5
    DLOAD 7
    DADD
    INVOKESTATIC java/lang/Double.valueOf (D)Ljava/lang/Double;
    AASTORE
    ARETURN
   L1
    LOCALVARIABLE arg1 I L0 L1 1
    LOCALVARIABLE arg2 I L0 L1 2
    LOCALVARIABLE arg3 F L0 L1 3
    LOCALVARIABLE arg4 F L0 L1 4
    LOCALVARIABLE arg5 D L0 L1 5
    LOCALVARIABLE arg6 D L0 L1 6
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   Object[] myMethod(int arg1, int arg2, float arg3, float arg4, double arg5, double var7) {
      return new Object[]{arg1 + arg2, arg3 + arg4, arg5 + var7};
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void concatStrings() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(String.class)
                .addParameters(Object.class)
                .addParameters(String[].class)
                .build((aThis, methodParameters) -> JavaIdioms.concatStrings(methodParameters).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/String;)Ljava/lang/String;
   L0
    NEW java/lang/StringBuilder
    DUP
    ALOAD 1
    INVOKESPECIAL java/lang/StringBuilder.<init> (Ljava/lang/String;)V
    ALOAD 2
    INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/Object;)Ljava/lang/StringBuilder;
    ALOAD 3
    INVOKESTATIC java/util/Arrays.toString ([Ljava/lang/String;)Ljava/lang/String;
    INVOKEVIRTUAL java/lang/StringBuilder.append (Ljava/lang/String;)Ljava/lang/StringBuilder;
    INVOKEVIRTUAL java/lang/StringBuilder.toString ()Ljava/lang/String;
    ARETURN
   L1
    LOCALVARIABLE arg1 Ljava/lang/String; L0 L1 1
    LOCALVARIABLE arg2 Ljava/lang/Object; L0 L1 2
    LOCALVARIABLE arg3 [Ljava/lang/String; L0 L1 3
}
""", bytecode);

        Assertions.assertEquals("""
package example;

import java.util.Arrays;

public class Example {
   String myMethod(String arg1, Object arg2, String[] arg3) {
      return arg1 + arg2 + Arrays.toString(arg3);
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void toStringTest() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(Object.class)
                .build((aThis, methodParameters) -> JavaIdioms.convertToStringIfNeeded(methodParameters.get(0)).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(Ljava/lang/Object;)Ljava/lang/String;
   L0
    ALOAD 1
    INVOKESTATIC java/lang/String.valueOf (Ljava/lang/Object;)Ljava/lang/String;
    ARETURN
   L1
    LOCALVARIABLE arg1 Ljava/lang/Object; L0 L1 1
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   String myMethod(Object arg1) {
      return String.valueOf(arg1);
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void instanceOf() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(Object.class)
                .build((aThis, methodParameters) -> methodParameters.get(0).instanceOf(TypeDef.STRING).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(Ljava/lang/Object;)Z
   L0
    ALOAD 1
    INSTANCEOF java/lang/String
    IRETURN
   L1
    LOCALVARIABLE arg1 Ljava/lang/Object; L0 L1 1
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   boolean myMethod(Object arg1) {
      return arg1 instanceof String;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void instanceOfPrimitive() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(int.class)
                .build((aThis, methodParameters) -> methodParameters.get(0).instanceOf(TypeDef.STRING).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod(I)Z
   L0
    ILOAD 1
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    INSTANCEOF java/lang/String
    IRETURN
   L1
    LOCALVARIABLE arg1 I L0 L1 1
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   boolean myMethod(int arg1) {
      return arg1 instanceof String;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void arrayElement() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(String[].class)
                .build((aThis, methodParameters) -> methodParameters.get(0).arrayElement(1).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod([Ljava/lang/String;)Ljava/lang/String;
   L0
    ALOAD 1
    ICONST_1
    AALOAD
    ARETURN
   L1
    LOCALVARIABLE arg1 [Ljava/lang/String; L0 L1 1
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   String myMethod(String[] arg1) {
      return arg1[1];
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void arrayElement2() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("myMethod")
                .addParameters(String[].class, int.class)
                .build((aThis, methodParameters) -> methodParameters.get(0).arrayElement(methodParameters.get(1)).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  myMethod([Ljava/lang/String;I)Ljava/lang/String;
   L0
    ALOAD 1
    ILOAD 2
    AALOAD
    ARETURN
   L1
    LOCALVARIABLE arg1 [Ljava/lang/String; L0 L1 1
    LOCALVARIABLE arg2 I L0 L1 2
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
   String myMethod(String[] arg1, int arg2) {
      return arg1[arg2];
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testSynthetic() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .synthetic()
            .addField(FieldDef.builder("myField", String.class).synthetic().build())
            .addMethod(MethodDef.builder("myMethod").synthetic().returns(int.class)
                .build((aThis, methodParameters) -> ExpressionDef.constant(1).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1001
// signature Ljava/lang/Object;
// declaration: example/Example
public synthetic class example/Example {


  // access flags 0x1000
  synthetic Ljava/lang/String; myField

  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1000
  synthetic myMethod()I
    ICONST_1
    IRETURN
}
""", bytecode);

        Assertions.assertEquals("""
package example;

// $FF: synthetic class
public class Example {
   // $FF: synthetic field
   String myField;

   // $FF: synthetic method
   int myMethod() {
      return 1;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testDefaultPublicConstructor() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/Example
public class example/Example {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN
}
""", bytecode);

        Assertions.assertEquals("""
package example;

public class Example {
}
""", decompileToJava(bytes));
    }

    @Test
    void testDefaultPackagePrivateConstructor() {
        ClassDef def = ClassDef.builder("example.Example")
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(def, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        Assertions.assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Example
class example/Example {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN
}
""", bytecode);

        Assertions.assertEquals("""
package example;

class Example {
}
""", decompileToJava(bytes));
    }

    @Test
    void testNullCondition() {
        ClassDef ifPredicateDef = ClassDef.builder("example.IfPredicate")
            .addMethod(MethodDef.builder("test").addParameter("param", TypeDef.OBJECT.makeNullable())
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(boolean.class)
                .build((aThis, methodParameters) -> StatementDef.multi(
                    methodParameters.get(0).isNull().doIf(TypeDef.Primitive.TRUE.returning()),
                    TypeDef.Primitive.FALSE.returning()
                )))
            .build();

        var bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(ifPredicateDef, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();
        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/IfPredicate
class example/IfPredicate {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public test(Ljava/lang/Object;)Z
   L0
    ALOAD 1
    IFNONNULL L1
    ICONST_1
    IRETURN
   L1
    ICONST_0
    IRETURN
   L2
    LOCALVARIABLE param Ljava/lang/Object; L0 L2 1
}
""", bytecode);

        assertEquals("""
package example;

class IfPredicate {
   public boolean test(Object param) {
      return param == null;
   }
}
""", decompileToJava(bytes));
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

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(ifPredicateDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/IfPredicate
class example/IfPredicate {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public test(Ljava/lang/Integer;)I
   L0
    ALOAD 1
    CHECKCAST java/lang/Number
    INVOKEVIRTUAL java/lang/Number.intValue ()I
    IRETURN
   L1
    LOCALVARIABLE param Ljava/lang/Integer; L0 L1 1
}
""", bytecode);

        assertEquals("""
package example;

class IfPredicate {
   public int test(Integer param) {
      return ((Number)param).intValue();
   }
}
""", decompileToJava(bytes));
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

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(ifPredicateDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/IfPredicate
class example/IfPredicate {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public test(I)Ljava/lang/Integer;
   L0
    ILOAD 1
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    ARETURN
   L1
    LOCALVARIABLE param I L0 L1 1
}
""", bytecode);


        assertEquals("""
package example;

class IfPredicate {
   public Integer test(int param) {
      return param;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testEnum() {
        EnumDef myEnum = EnumDef.builder("MyEnum").addEnumConstant("A").addEnumConstant("B").addEnumConstant("C").build();

        String bytecode = toBytecode(myEnum);
        assertEquals("""
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

  // access flags 0xA
  private static [LMyEnum; $VALUES

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

  // access flags 0x2
  private <init>(Ljava/lang/String;I)V
   L0
    ALOAD 0
    ALOAD 1
    ILOAD 2
    INVOKESPECIAL java/lang/Enum.<init> (Ljava/lang/String;I)V
    RETURN
   L1
    LOCALVARIABLE arg0 Ljava/lang/String; L0 L1 1
    LOCALVARIABLE arg1 I L0 L1 2

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

  // access flags 0x9
  public static values()[LMyEnum;
    GETSTATIC MyEnum.$VALUES : [LMyEnum;
    INVOKEVIRTUAL [LMyEnum;.clone ()Ljava/lang/Object;
    CHECKCAST [LMyEnum;
    ARETURN

  // access flags 0x9
  public static valueOf(Ljava/lang/String;)LMyEnum;
   L0
    LDC LMyEnum;.class
    ALOAD 0
    INVOKESTATIC java/lang/Enum.valueOf (Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;
    CHECKCAST MyEnum
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/String; L0 L1 1
}
""", bytecode);
    }

    @Test
    void testSynchronized() {
        FieldDef beanResolutionContextField = FieldDef.builder("$beanResolutionContext", BeanResolutionContext.class)
            .addModifiers(Modifier.PRIVATE)
            .build();
        FieldDef targetField = FieldDef.builder("target", ClassTypeDef.of("test.SomeTarget")).addModifiers(Modifier.PRIVATE).build();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addField(beanResolutionContextField)
            .addField(targetField)
            .addMethod(
                MethodDef.builder("interceptedTarget")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeDef.OBJECT)
                    .build((aThis, methodParameters) -> {
                        VariableDef.Field targetFieldAccess = aThis.field(targetField);
                        return StatementDef.multi(
                            targetFieldAccess.newLocal("target", targetVar ->
                                targetVar.ifNull(
                                    new StatementDef.Synchronized(
                                        aThis,
                                        StatementDef.multi(
                                            targetVar.assign(targetFieldAccess),
                                            targetVar.ifNull(
                                                StatementDef.multi(
                                                    targetFieldAccess.assign(ExpressionDef.nullValue()),
                                                    aThis.field(beanResolutionContextField).assign(ExpressionDef.nullValue())
                                                )
                                            )
                                        )
                                    )
                                )
                            ),
                            targetFieldAccess.returning()
                        );
                    })
            ).build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: test/MyClass
class test/MyClass {


  // access flags 0x2
  private Lio/micronaut/context/BeanResolutionContext; $beanResolutionContext

  // access flags 0x2
  private Ltest/SomeTarget; target

  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public interceptedTarget()Ljava/lang/Object;
   L0
    ALOAD 0
    GETFIELD test/MyClass.target : Ltest/SomeTarget;
    ASTORE 1
    ALOAD 1
    IFNONNULL L1
    TRYCATCHBLOCK L2 L3 L4 null
    TRYCATCHBLOCK L4 L5 L4 null
    ALOAD 0
    DUP
    ASTORE 2
    MONITORENTER
   L2
    ALOAD 0
    GETFIELD test/MyClass.target : Ltest/SomeTarget;
    ASTORE 1
    ALOAD 1
    IFNONNULL L6
    ALOAD 0
    ACONST_NULL
    PUTFIELD test/MyClass.target : Ltest/SomeTarget;
    ALOAD 0
    ACONST_NULL
    PUTFIELD test/MyClass.$beanResolutionContext : Lio/micronaut/context/BeanResolutionContext;
   L6
    ALOAD 2
    MONITOREXIT
   L3
    GOTO L7
   L4
    ASTORE 3
    ALOAD 2
    MONITOREXIT
   L5
    ALOAD 3
    ATHROW
   L7
   L1
    ALOAD 0
    GETFIELD test/MyClass.target : Ltest/SomeTarget;
    ARETURN
   L8
    LOCALVARIABLE target Ltest/SomeTarget; L0 L8 1
}
""", bytecode);


        assertEquals("""
package test;

import io.micronaut.context.BeanResolutionContext;

class MyClass {
   private BeanResolutionContext $beanResolutionContext;
   private SomeTarget target;

   public Object interceptedTarget() {
      SomeTarget target = this.target;
      if (target == null) {
         synchronized(this) {
            target = this.target;
            if (target == null) {
               this.target = null;
               this.$beanResolutionContext = null;
            }
         }
      }

      return this.target;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testFinally() {
        FieldDef myField = FieldDef.builder("myField", Integer.class)
            .addModifiers(Modifier.PRIVATE)
            .build();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addField(myField)
            .addMethod(
                MethodDef.builder("interceptedTarget")
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeDef.OBJECT)
                    .build((aThis, methodParameters) -> {
                        VariableDef.Field myFieldAccess = aThis.field(myField);
                        return StatementDef.multi(
                            myFieldAccess.ifNull(
                                ExpressionDef.constant(1).returning()
                            ),
                            myFieldAccess.ifNonNull(
                                ExpressionDef.constant(2).returning()
                            ),
                            myFieldAccess.returning()
                        ).doTry().doFinally(ClassTypeDef.of(System.class)
                            .getStaticField("out", TypeDef.of(PrintStream.class))
                            .invoke("println", TypeDef.VOID, ExpressionDef.constant("Hello")));
                    })
            ).build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: test/MyClass
class test/MyClass {


  // access flags 0x2
  private Ljava/lang/Integer; myField

  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public interceptedTarget()Ljava/lang/Object;
    TRYCATCHBLOCK L0 L1 L2 null
   L0
    ALOAD 0
    GETFIELD test/MyClass.myField : Ljava/lang/Integer;
    IFNONNULL L3
    ICONST_1
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    ASTORE 1
    GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
    LDC "Hello"
    INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
    ALOAD 1
    ARETURN
   L3
    ALOAD 0
    GETFIELD test/MyClass.myField : Ljava/lang/Integer;
    IFNULL L4
    ICONST_2
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    ASTORE 2
    GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
    LDC "Hello"
    INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
    ALOAD 2
    ARETURN
   L4
    ALOAD 0
    GETFIELD test/MyClass.myField : Ljava/lang/Integer;
    ASTORE 3
    GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
    LDC "Hello"
    INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
    ALOAD 3
    ARETURN
   L1
    GOTO L5
   L2
    ASTORE 4
    GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
    LDC "Hello"
    INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
    ALOAD 4
    ATHROW
    GOTO L5
   L5
}
""", bytecode);

        assertEquals("""
package test;

class MyClass {
   private Integer myField;

   public Object interceptedTarget() {
      try {
         if (this.myField == null) {
            Integer var1 = 1;
            System.out.println("Hello");
            return var1;
         } else if (this.myField != null) {
            Integer var2 = 2;
            System.out.println("Hello");
            return var2;
         } else {
            Integer var3 = this.myField;
            System.out.println("Hello");
            return var3;
         }
      } finally {
         System.out.println("Hello");
      }
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testFinally2 () {
        FieldDef targetField = FieldDef.builder("target", Object.class)
            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
            .build();

        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addField(targetField)
            .addMethod(
                MethodDef.builder("swap")
                    .addModifiers(Modifier.PUBLIC)
                    .addParameters(Object.class)
                    .returns(Object.class)
                    .build((aThis, methodParameters) -> StatementDef.multi(
                        ClassTypeDef.of(System.class)
                            .getStaticField("out", TypeDef.of(PrintStream.class))
                            .invoke("println", TypeDef.VOID, ExpressionDef.constant("Hello")),
                        StatementDef.doTry(
                            aThis.field(targetField).newLocal("target", targetVar -> StatementDef.multi(
                                aThis.field(targetField).assign(methodParameters.get(0)),
                                targetVar.returning()
                            ))
                        ).doFinally(ClassTypeDef.of(System.class)
                            .getStaticField("out", TypeDef.of(PrintStream.class))
                            .invoke("println", TypeDef.VOID, ExpressionDef.constant("World")))
                    ))
            ).build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: test/MyClass
class test/MyClass {


  // access flags 0x12
  private final Ljava/lang/Object; target

  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public swap(Ljava/lang/Object;)Ljava/lang/Object;
   L0
    GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
    LDC "Hello"
    INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
    TRYCATCHBLOCK L1 L2 L3 null
   L1
   L4
    ALOAD 0
    GETFIELD test/MyClass.target : Ljava/lang/Object;
    ASTORE 2
    ALOAD 0
    ALOAD 1
    PUTFIELD test/MyClass.target : Ljava/lang/Object;
    ALOAD 2
    ASTORE 3
    GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
    LDC "World"
    INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
    ALOAD 3
    ARETURN
   L5
    LOCALVARIABLE target Ljava/lang/Object; L4 L5 2
   L2
    GOTO L6
   L3
    ASTORE 4
    GETSTATIC java/lang/System.out : Ljava/io/PrintStream;
    LDC "World"
    INVOKEVIRTUAL java/io/PrintStream.println (Ljava/lang/String;)V
    ALOAD 4
    ATHROW
    GOTO L6
   L6
   L7
    LOCALVARIABLE arg1 Ljava/lang/Object; L0 L7 1
}
""", bytecode);

        assertEquals("""
package test;

class MyClass {
   private final Object target;

   public Object swap(Object arg1) {
      System.out.println("Hello");

      try {
         Object target = this.target;
         this.target = arg1;
         System.out.println("World");
         return target;
      } finally {
         System.out.println("World");
      }
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testTableSwitchWithDuplicateStatements() {
        StatementDef sameStatement = TypeDef.Primitive.INT.constant(123).returning();
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(
                MethodDef.builder("swap")
                    .addModifiers(Modifier.PUBLIC)
                    .addParameters(int.class)
                    .returns(int.class)
                    .build((aThis, methodParameters) -> methodParameters.get(0)
                        .asStatementSwitch(
                            TypeDef.Primitive.INT,
                            Map.of(
                                ExpressionDef.constant(1), sameStatement,
                                ExpressionDef.constant(2), TypeDef.Primitive.INT.constant(111).returning(),
                                ExpressionDef.constant(3), sameStatement,
                                ExpressionDef.constant(4), sameStatement,
                                ExpressionDef.constant(5), sameStatement,
                                ExpressionDef.constant(6), sameStatement,
                                ExpressionDef.constant(7), sameStatement
                            ),
                            TypeDef.Primitive.INT.constant(444).returning()
                        )
                    )
            ).build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: test/MyClass
class test/MyClass {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public swap(I)I
   L0
    ILOAD 1
    TABLESWITCH
      1: L1
      2: L2
      3: L1
      4: L1
      5: L1
      6: L1
      7: L1
      default: L3
   L1
    BIPUSH 123
    IRETURN
    GOTO L4
   L2
    BIPUSH 111
    IRETURN
    GOTO L4
   L3
    SIPUSH 444
    IRETURN
   L4
   L5
    LOCALVARIABLE arg1 I L0 L5 1
}
""", bytecode);

        assertEquals("""
package test;

class MyClass {
   public int swap(int arg1) {
      switch (arg1) {
         case 1:
         case 3:
         case 4:
         case 5:
         case 6:
         case 7:
            return 123;
         case 2:
            return 111;
         default:
            return 444;
      }
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testTableLookupWithDuplicateStatements() {
        StatementDef sameStatement = TypeDef.Primitive.INT.constant(123).returning();
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(
                MethodDef.builder("swap")
                    .addModifiers(Modifier.PUBLIC)
                    .addParameters(int.class)
                    .returns(int.class)
                    .build((aThis, methodParameters) -> methodParameters.get(0)
                        .asStatementSwitch(
                            TypeDef.Primitive.INT,
                            Map.of(
                                ExpressionDef.constant(1), sameStatement,
                                ExpressionDef.constant(20), TypeDef.Primitive.INT.constant(111).returning(),
                                ExpressionDef.constant(30), sameStatement,
                                ExpressionDef.constant(40), sameStatement,
                                ExpressionDef.constant(50), sameStatement,
                                ExpressionDef.constant(60), sameStatement,
                                ExpressionDef.constant(70), sameStatement
                            ),
                            TypeDef.Primitive.INT.constant(444).returning()
                        )
                    )
            ).build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: test/MyClass
class test/MyClass {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public swap(I)I
   L0
    ILOAD 1
    LOOKUPSWITCH
      1: L1
      20: L2
      30: L1
      40: L1
      50: L1
      60: L1
      70: L1
      default: L3
   L1
    BIPUSH 123
    IRETURN
    GOTO L4
   L2
    BIPUSH 111
    IRETURN
    GOTO L4
   L3
    SIPUSH 444
    IRETURN
   L4
   L5
    LOCALVARIABLE arg1 I L0 L5 1
}
""", bytecode);

        assertEquals("""
package test;

class MyClass {
   public int swap(int arg1) {
      switch (arg1) {
         case 1:
         case 30:
         case 40:
         case 50:
         case 60:
         case 70:
            return 123;
         case 20:
            return 111;
         default:
            return 444;
      }
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testInnerClass() {
        ClassDef classDef = ClassDef.builder("test.MyClass")
            .addMethod(
                MethodDef.builder("hello")
                    .build((aThis, methodParameters) -> ExpressionDef.constant("world").returning())
            ).addInnerType(ClassDef.builder("Inner")
                .addMethod(
                    MethodDef.builder("hi")
                        .build((aThis, methodParameters) -> ExpressionDef.constant("hello").returning())
                ).build())
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: test/MyClass
class test/MyClass {

  // access flags 0x9
  public static INNERCLASS test/MyClass$Inner test/MyClass Inner
  NESTMEMBER test/MyClass$Inner

  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  hello()Ljava/lang/String;
    LDC "world"
    ARETURN
}
""", bytecode);

        assertEquals("""
package test;

class MyClass {
   String hello() {
      return "world";
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testInnerEnum() {
        EnumDef enumDef = GenerateInnerTypeInEnumVisitor.getEnumDef("example", VisitorContext.Language.JAVA);

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(enumDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x4011
// signature Ljava/lang/Enum<Lexample/MyEnumWithInnerTypes;>;
// declaration: example/MyEnumWithInnerTypes extends java.lang.Enum<example.MyEnumWithInnerTypes>
public final enum example/MyEnumWithInnerTypes extends java/lang/Enum {

  // access flags 0x4019
  public final static enum INNERCLASS example/MyEnumWithInnerTypes$InnerEnum example/MyEnumWithInnerTypes InnerEnum
  NESTMEMBER example/MyEnumWithInnerTypes$InnerEnum
  // access flags 0x9
  public static INNERCLASS example/MyEnumWithInnerTypes$InnerRecord example/MyEnumWithInnerTypes InnerRecord
  NESTMEMBER example/MyEnumWithInnerTypes$InnerRecord
  // access flags 0x9
  public static INNERCLASS example/MyEnumWithInnerTypes$InnerClass example/MyEnumWithInnerTypes InnerClass
  NESTMEMBER example/MyEnumWithInnerTypes$InnerClass
  // access flags 0x609
  public static abstract INNERCLASS example/MyEnumWithInnerTypes$InnerInterface example/MyEnumWithInnerTypes InnerInterface
  NESTMEMBER example/MyEnumWithInnerTypes$InnerInterface

  // access flags 0x4019
  public final static enum Lexample/MyEnumWithInnerTypes; A

  // access flags 0x4019
  public final static enum Lexample/MyEnumWithInnerTypes; B

  // access flags 0x4019
  public final static enum Lexample/MyEnumWithInnerTypes; C

  // access flags 0xA
  private static [Lexample/MyEnumWithInnerTypes; $VALUES

  // access flags 0x8
  static <clinit>()V
    NEW example/MyEnumWithInnerTypes
    DUP
    LDC "A"
    ICONST_0
    INVOKESPECIAL example/MyEnumWithInnerTypes.<init> (Ljava/lang/String;I)V
    PUTSTATIC example/MyEnumWithInnerTypes.A : Lexample/MyEnumWithInnerTypes;
    NEW example/MyEnumWithInnerTypes
    DUP
    LDC "B"
    ICONST_1
    INVOKESPECIAL example/MyEnumWithInnerTypes.<init> (Ljava/lang/String;I)V
    PUTSTATIC example/MyEnumWithInnerTypes.B : Lexample/MyEnumWithInnerTypes;
    NEW example/MyEnumWithInnerTypes
    DUP
    LDC "C"
    ICONST_2
    INVOKESPECIAL example/MyEnumWithInnerTypes.<init> (Ljava/lang/String;I)V
    PUTSTATIC example/MyEnumWithInnerTypes.C : Lexample/MyEnumWithInnerTypes;
    INVOKESTATIC example/MyEnumWithInnerTypes.$values ()[Lexample/MyEnumWithInnerTypes;
    PUTSTATIC example/MyEnumWithInnerTypes.$VALUES : [Lexample/MyEnumWithInnerTypes;
    RETURN

  // access flags 0x2
  private <init>(Ljava/lang/String;I)V
   L0
    ALOAD 0
    ALOAD 1
    ILOAD 2
    INVOKESPECIAL java/lang/Enum.<init> (Ljava/lang/String;I)V
    RETURN
   L1
    LOCALVARIABLE arg0 Ljava/lang/String; L0 L1 1
    LOCALVARIABLE arg1 I L0 L1 2

  // access flags 0xA
  private static $values()[Lexample/MyEnumWithInnerTypes;
    ICONST_3
    ANEWARRAY example/MyEnumWithInnerTypes
    DUP
    ICONST_0
    GETSTATIC example/MyEnumWithInnerTypes.A : Lexample/MyEnumWithInnerTypes;
    AASTORE
    DUP
    ICONST_1
    GETSTATIC example/MyEnumWithInnerTypes.B : Lexample/MyEnumWithInnerTypes;
    AASTORE
    DUP
    ICONST_2
    GETSTATIC example/MyEnumWithInnerTypes.C : Lexample/MyEnumWithInnerTypes;
    AASTORE
    ARETURN

  // access flags 0x9
  public static values()[Lexample/MyEnumWithInnerTypes;
    GETSTATIC example/MyEnumWithInnerTypes.$VALUES : [Lexample/MyEnumWithInnerTypes;
    INVOKEVIRTUAL [Lexample/MyEnumWithInnerTypes;.clone ()Ljava/lang/Object;
    CHECKCAST [Lexample/MyEnumWithInnerTypes;
    ARETURN

  // access flags 0x9
  public static valueOf(Ljava/lang/String;)Lexample/MyEnumWithInnerTypes;
   L0
    LDC Lexample/MyEnumWithInnerTypes;.class
    ALOAD 0
    INVOKESTATIC java/lang/Enum.valueOf (Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;
    CHECKCAST example/MyEnumWithInnerTypes
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/String; L0 L1 1

  // access flags 0x1
  public myName()Ljava/lang/String;
    ALOAD 0
    INVOKEVIRTUAL example/MyEnumWithInnerTypes.toString ()Ljava/lang/String;
    ARETURN
}
""", bytecode);

        assertEquals("""
package example;

public enum MyEnumWithInnerTypes {
   A,
   B,
   C;

   private static MyEnumWithInnerTypes[] $VALUES = $values();

   private static MyEnumWithInnerTypes[] $values() {
      return new MyEnumWithInnerTypes[]{A, B, C};
   }

   public String myName() {
      return this.toString();
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testLocalVariable() {
        MethodDef method = MethodDef.builder("test").addParameter("myIn", String.class)
            .returns(int.class)
            .build((aThis, methodParameters) -> {
                return methodParameters.get(0).invokeHashCode().newLocal("hc", hcVar -> {
                    return hcVar.math(ADDITION, TypeDef.Primitive.INT.constant(4)).returning();
                });
            });
        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(method)
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Test
class example/Test {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  test(Ljava/lang/String;)I
   L0
   L1
    ALOAD 1
    IFNONNULL L2
    ICONST_0
    GOTO L3
   L2
    ALOAD 1
    INVOKEVIRTUAL java/lang/String.hashCode ()I
   L3
    ISTORE 2
    ILOAD 2
    ICONST_4
    IADD
    IRETURN
   L4
    LOCALVARIABLE myIn Ljava/lang/String; L0 L4 1
    LOCALVARIABLE hc I L1 L4 2
}
""", bytecode);

        assertEquals("""
package example;

class Test {
   int test(String myIn) {
      int hc = myIn == null ? 0 : myIn.hashCode();
      return hc + 4;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testMultipleCasts() {
        MethodDef method = MethodDef.builder("test").addParameter("myIn", Object.class)
            .returns(Integer.class)
            .build((aThis, methodParameters) -> methodParameters.get(0).cast(TypeDef.Primitive.INT).cast(TypeDef.of(Integer.class)).returning());
        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(method)
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Test
class example/Test {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  test(Ljava/lang/Object;)Ljava/lang/Integer;
   L0
    ALOAD 1
    CHECKCAST java/lang/Integer
    ARETURN
   L1
    LOCALVARIABLE myIn Ljava/lang/Object; L0 L1 1
}
""", bytecode);

        assertEquals("""
package example;

class Test {
   Integer test(Object myIn) {
      return (Integer)myIn;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testNullCast() {
        MethodDef method = MethodDef.builder("test").addParameter("myIn", Object.class)
            .returns(Integer.class)
            .build((aThis, methodParameters) -> ExpressionDef.nullValue().cast(TypeDef.of(Integer.class)).returning());
        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(method)
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Test
class example/Test {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  test(Ljava/lang/Object;)Ljava/lang/Integer;
   L0
    ACONST_NULL
    ARETURN
   L1
    LOCALVARIABLE myIn Ljava/lang/Object; L0 L1 1
}
""", bytecode);

        assertEquals("""
package example;

class Test {
   Integer test(Object myIn) {
      return null;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testTrueFalseConditions() {
        MethodDef method = MethodDef.builder("test")
            .returns(boolean.class)
            .build((aThis, methodParameters) ->
                new ExpressionDef.Or(
                    ExpressionDef.trueValue().isTrue().and(ExpressionDef.falseValue().isTrue()),
                    ExpressionDef.trueValue().isTrue().or(ExpressionDef.falseValue().isTrue())
                ).returning());
        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(method)
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Test
class example/Test {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  test()Z
    ICONST_1
    ICONST_1
    IF_ICMPNE L0
    ICONST_0
    ICONST_1
    IF_ICMPNE L0
    GOTO L1
   L0
    ICONST_1
    ICONST_1
    IF_ICMPEQ L1
    ICONST_0
    ICONST_1
    IF_ICMPEQ L1
    GOTO L2
   L1
    ICONST_1
    GOTO L3
   L2
    ICONST_0
   L3
    IRETURN
}
""", bytecode);

        assertEquals("""
package example;

class Test {
   boolean test() {
      return true && false || true || false;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testTrueFalseConditions2() {
        MethodDef method = MethodDef.builder("test")
            .returns(boolean.class)
            .build((aThis, methodParameters) ->
                new ExpressionDef.And(
                    ExpressionDef.trueValue().isTrue().or(ExpressionDef.falseValue().isTrue()),
                    ExpressionDef.trueValue().isTrue().or(ExpressionDef.falseValue().isTrue())
                ).returning());
        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(method)
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Test
class example/Test {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  test()Z
    ICONST_1
    ICONST_1
    IF_ICMPEQ L0
    ICONST_0
    ICONST_1
    IF_ICMPEQ L0
    GOTO L1
   L0
    ICONST_1
    ICONST_1
    IF_ICMPEQ L2
    ICONST_0
    ICONST_1
    IF_ICMPEQ L2
    GOTO L1
   L2
    ICONST_1
    GOTO L3
   L1
    ICONST_0
   L3
    IRETURN
}
""", bytecode);

        assertEquals("""
package example;

class Test {
   boolean test() {
      return (true || false) && (true || false);
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testPrimitives() {

        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(MethodDef.builder("testLong")
                .returns(TypeDef.Primitive.LONG)
                .build((aThis, methodParameters) ->
                    TypeDef.Primitive.LONG.constant(123445).returning()
                )
            )
            .addMethod(MethodDef.builder("testInt")
                .returns(TypeDef.Primitive.INT)
                .build((aThis, methodParameters) ->
                    TypeDef.Primitive.INT.constant(334455).returning()
                )
            )
            .addMethod(MethodDef.builder("testFloat")
                .returns(TypeDef.Primitive.FLOAT)
                .build((aThis, methodParameters) ->
                    TypeDef.Primitive.FLOAT.constant(123.456).returning()
                )
            )
            .addMethod(MethodDef.builder("testShort")
                .returns(TypeDef.Primitive.SHORT)
                .build((aThis, methodParameters) ->
                    TypeDef.Primitive.SHORT.constant(345).returning()
                )
            )
            .addMethod(MethodDef.builder("testChar")
                .returns(TypeDef.Primitive.CHAR)
                .build((aThis, methodParameters) ->
                    TypeDef.Primitive.CHAR.constant('c').returning()
                )
            )
            .addMethod(MethodDef.builder("testBoolean")
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((aThis, methodParameters) ->
                    TypeDef.Primitive.BOOLEAN.constant(true).returning()
                )
            )
            .addMethod(MethodDef.builder("testByte")
                .returns(TypeDef.Primitive.BYTE)
                .build((aThis, methodParameters) ->
                    TypeDef.Primitive.BYTE.constant(45).returning()
                )
            )
            .addMethod(MethodDef.builder("testDouble")
                .returns(TypeDef.Primitive.DOUBLE)
                .build((aThis, methodParameters) ->
                    TypeDef.Primitive.DOUBLE.constant(444.555).returning()
                )
            )
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);

        assertEquals("""
package example;

class Test {
   long testLong() {
      return 123445L;
   }

   int testInt() {
      return 334455;
   }

   float testFloat() {
      return 123.456F;
   }

   short testShort() {
      return 345;
   }

   char testChar() {
      return 'c';
   }

   boolean testBoolean() {
      return true;
   }

   byte testByte() {
      return 45;
   }

   double testDouble() {
      return 444.555;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testCastingNull() {

        MethodDef build = MethodDef.builder("acceptValue")
            .addParameter(TypeDef.of(Integer.class))
            .returns(TypeDef.OBJECT)
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(build
            )
            .addMethod(MethodDef.builder("invoke")
                .build((aThis, methodParameters) ->
                    aThis.invoke(build, ExpressionDef.nullValue()).returning()
                )
            )
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Test
class example/Test {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  acceptValue(Ljava/lang/Integer;)Ljava/lang/Object;
   L0
    ALOAD 1
    ARETURN
   L1
    LOCALVARIABLE arg1 Ljava/lang/Integer; L0 L1 1

  // access flags 0x0
  invoke()Ljava/lang/Object;
    ALOAD 0
    ACONST_NULL
    INVOKEVIRTUAL example/Test.acceptValue (Ljava/lang/Integer;)Ljava/lang/Object;
    ARETURN
}
""", bytecode);

        assertEquals("""
package example;

class Test {
   Object acceptValue(Integer arg1) {
      return arg1;
   }

   Object invoke() {
      return this.acceptValue((Integer)null);
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testCastingClassDefWithSuperclass() {

        ClassDef myList = ClassDef.builder("example.MyList")
            .superclass(ClassTypeDef.of(AbstractList.class))
            .build();

        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(MethodDef.builder("load")
                .returns(ClassTypeDef.of(List.class))
                .build((aThis, methodParameters) -> myList.asTypeDef()
                    .instantiate().returning())
            )
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Test
class example/Test {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  load()Ljava/util/List;
    NEW example/MyList
    DUP
    INVOKESPECIAL example/MyList.<init> ()V
    ARETURN
}
""", bytecode);

        assertEquals("""
package example;

import java.util.List;

class Test {
   List load() {
      return new MyList();
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testCastingThisClassDefWithSuperclass() {

        ClassDef classDef = ClassDef.builder("example.Test")
            .superclass(TypeDef.parameterized(AbstractList.class, Number.class))
            .addMethod(MethodDef.builder("load")
                .returns(ClassTypeDef.of(List.class))
                .build((aThis, methodParameters) -> aThis.type().instantiate().returning())
            )
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/util/AbstractList<Ljava/lang/Number;>;
// declaration: example/Test extends java.util.AbstractList<java.lang.Number>
class example/Test extends java/util/AbstractList {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/util/AbstractList.<init> ()V
    RETURN

  // access flags 0x0
  load()Ljava/util/List;
    NEW example/Test
    DUP
    INVOKESPECIAL example/Test.<init> ()V
    ARETURN
}
""", bytecode);

        assertEquals("""
package example;

import java.util.AbstractList;
import java.util.List;

class Test extends AbstractList {
   List load() {
      return new Test();
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testCastingEnum() {

        EnumDef enumDef = EnumDef.builder("example.MyEnum")
            .addEnumConstant("A")
            .addEnumConstant("B")
            .build();

        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(MethodDef.builder("load")
                .returns(Enum.class)
                .build((aThis, methodParameters) -> enumDef.asTypeDef()
                    .getStaticField(enumDef.getField("A"))
                    .returning())
            )
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);

        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Test
class example/Test {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  load()Ljava/lang/Enum;
    GETSTATIC example/MyEnum.A : Lexample/MyEnum;
    ARETURN
}
""", bytecode);

        assertEquals("""
package example;

class Test {
   Enum load() {
      return MyEnum.A;
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testStringConcatenation() {
        ClassDef classDef = ClassDef.builder("example.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("testConcatenation")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("arg", TypeDef.OBJECT)
                .returns(TypeDef.STRING)
                .build((t, params) ->
                    ExpressionDef.constant("Hello, ")
                        .stringConcat(params.get(0))
                        .stringConcat(ExpressionDef.constant("!"))
                        .returning()
                )
            )
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;
// declaration: example/MyClass
public class example/MyClass {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public testConcatenation(Ljava/lang/Object;)Ljava/lang/String;
   L0
    LDC "Hello, "
    ALOAD 1
    LDC "!"
    INVOKEDYNAMIC makeConcatWithConstants(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String; [
      // handle kind 0x6 : INVOKESTATIC
      java/lang/invoke/StringConcatFactory.makeConcatWithConstants(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;
      // arguments:
      "\\u0001\\u0001\\u0001"
    ]
    ARETURN
   L1
    LOCALVARIABLE arg Ljava/lang/Object; L0 L1 1
}
""", bytecode);
        assertEquals("""
package example;

public class MyClass {
   public String testConcatenation(Object arg) {
      return "Hello, " + arg + "!";
   }
}
""", decompileToJava(bytes));
    }

    private String toBytecode(ObjectDef objectDef) {
        StringWriter stringWriter = new StringWriter();
        generateFile(objectDef, stringWriter);
        return stringWriter.toString();
    }

    private byte[] generateFile(ObjectDef objectDef, StringWriter stringWriter) {
        var classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        var checkClassAdapter = new CheckClassAdapter(classWriter);
        var tcv = new TraceClassVisitor(checkClassAdapter, new PrintWriter(stringWriter));

        new ByteCodeWriter(false, false).writeObject(tcv, objectDef);

        tcv.visitEnd();

        return classWriter.toByteArray();
    }

    @Test
    void testMethodThrows() {
        MethodDef method = MethodDef.builder("test")
            .returns(TypeDef.VOID)
            .addThrows(TypeDef.of(IOException.class))
            .build((aThis, methodParameters) ->
                ClassTypeDef.of(IOException.class).instantiate().doThrow()
            );
        ClassDef classDef = ClassDef.builder("example.Test")
            .addMethod(method)
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;
// declaration: example/Test
class example/Test {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  test()V throws java/io/IOException
    NEW java/io/IOException
    DUP
    INVOKESPECIAL java/io/IOException.<init> ()V
    ATHROW
}
""", bytecode);

        assertEquals("""
package example;

import java.io.IOException;

class Test {
   void test() throws IOException {
      throw new IOException();
   }
}
""", decompileToJava(bytes));
    }

    @Test
    void testErasedInterface() {
        ClassElement element = ClassElement.of(
            Function.class,
            AnnotationMetadata.EMPTY_METADATA,
            Map.of(
                "T", ClassElement.of(String.class),
                "R", ClassElement.of(String.class)
            )
        );

        ClassDef classDef = ClassDef.builder("example.Test")
            .addSuperinterface(TypeDef.erasure(element))
            .addMethod(MethodDef.builder("apply").returns(TypeDef.OBJECT)
                .addParameter(TypeDef.OBJECT).build((t, params) -> params.get(0).returning()))
            .build();

        StringWriter bytecodeWriter = new StringWriter();
        byte[] bytes = generateFile(classDef, bytecodeWriter);
        String bytecode = bytecodeWriter.toString();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x0
// signature Ljava/lang/Object;Ljava/util/function/Function<Ljava/lang/String;Ljava/lang/String;>;
// declaration: example/Test implements java.util.function.Function<java.lang.String, java.lang.String>
class example/Test implements java/util/function/Function {


  // access flags 0x0
  <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x0
  apply(Ljava/lang/Object;)Ljava/lang/Object;
   L0
    ALOAD 1
    ARETURN
   L1
    LOCALVARIABLE arg1 Ljava/lang/Object; L0 L1 1
}
""", bytecode);

        assertEquals("""
package example;

import java.util.function.Function;

class Test implements Function {
   Object apply(Object arg1) {
      return arg1;
   }
}
""", decompileToJava(bytes));
    }

}
