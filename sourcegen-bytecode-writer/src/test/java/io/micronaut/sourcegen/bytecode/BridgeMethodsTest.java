/*
 * Copyright 2017-2024 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.sourcegen.bytecode;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.EnumDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.ParameterDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bridge methods are not declared in the model: the writer resolves them from the declared supertypes,
 * one per inherited method that a declared method overrides with a different erasure.
 */
class BridgeMethodsTest {

    @Test
    void testNonGenericCovariantReturnBridge() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(TypeDef.OBJECT).build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent))
            .addMethod(MethodDef.builder("self")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(ClassTypeDef.of("example.Example"))
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/Parent;
// declaration: example/Example extends example.Parent
public class example/Example extends example/Parent {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/Parent.<init> ()V
    RETURN

  // access flags 0x1
  public self()Lexample/Example;
    ALOAD 0
    ARETURN

  // access flags 0x1041
  public synthetic bridge self()Ljava/lang/Object;
    ALOAD 0
    INVOKEVIRTUAL example/Example.self ()Lexample/Example;
    ARETURN
}
""", generate(def));
    }

    @Test
    void testGenericParentErasedThroughItsBound() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T"))
                .returns(TypeDef.variable("T"))
                .build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(Integer.class)))
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.of(Integer.class))
                .returns(TypeDef.of(Integer.class))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        // T extends Number erases to Number at the declaration site
        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/Parent<Ljava/lang/Integer;>;
// declaration: example/Example extends example.Parent<java.lang.Integer>
public class example/Example extends example/Parent {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/Parent.<init> ()V
    RETURN

  // access flags 0x1
  public value(Ljava/lang/Integer;)Ljava/lang/Integer;
   L0
    ALOAD 1
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/Integer; L0 L1 1

  // access flags 0x1041
  public synthetic bridge value(Ljava/lang/Number;)Ljava/lang/Number;
   L0
    ALOAD 0
    ALOAD 1
    CHECKCAST java/lang/Integer
    INVOKEVIRTUAL example/Example.value (Ljava/lang/Integer;)Ljava/lang/Integer;
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/Number; L0 L1 1
}
""", generate(def));
    }

    @Test
    void testReflectedInterfaceErasesParameterAndReturn() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Function.class), TypeDef.of(String.class), TypeDef.of(Integer.class)))
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.of(String.class))
                .returns(TypeDef.of(Integer.class))
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke("length", TypeDef.Primitive.INT)
                    .cast(TypeDef.of(Integer.class))
                    .returning()))
            .build();

        // The JDK interface is only available through reflection
        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;Ljava/util/function/Function<Ljava/lang/String;Ljava/lang/Integer;>;
// declaration: example/Example implements java.util.function.Function<java.lang.String, java.lang.Integer>
public class example/Example implements java/util/function/Function {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public apply(Ljava/lang/String;)Ljava/lang/Integer;
   L0
    ALOAD 1
    INVOKEVIRTUAL java/lang/String.length ()I
    INVOKESTATIC java/lang/Integer.valueOf (I)Ljava/lang/Integer;
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/String; L0 L1 1

  // access flags 0x1041
  public synthetic bridge apply(Ljava/lang/Object;)Ljava/lang/Object;
   L0
    ALOAD 0
    ALOAD 1
    CHECKCAST java/lang/String
    INVOKEVIRTUAL example/Example.apply (Ljava/lang/String;)Ljava/lang/Integer;
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/Object; L0 L1 1
}
""", generate(def));
    }

    @Test
    void testReflectedInterfaceWithPrimitiveReturn() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Comparator.class), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("compare")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("first", TypeDef.of(String.class))
                .addParameter("second", TypeDef.of(String.class))
                .returns(TypeDef.Primitive.INT)
                .build((aThis, methodParameters) -> methodParameters.get(0)
                    .invoke("compareTo", TypeDef.Primitive.INT, methodParameters.get(1))
                    .returning()))
            .build();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Ljava/lang/Object;Ljava/util/Comparator<Ljava/lang/String;>;
// declaration: example/Example implements java.util.Comparator<java.lang.String>
public class example/Example implements java/util/Comparator {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL java/lang/Object.<init> ()V
    RETURN

  // access flags 0x1
  public compare(Ljava/lang/String;Ljava/lang/String;)I
   L0
    ALOAD 1
    ALOAD 2
    INVOKEVIRTUAL java/lang/String.compareTo (Ljava/lang/String;)I
    IRETURN
   L1
    LOCALVARIABLE first Ljava/lang/String; L0 L1 1
    LOCALVARIABLE second Ljava/lang/String; L0 L1 2

  // access flags 0x1041
  public synthetic bridge compare(Ljava/lang/Object;Ljava/lang/Object;)I
   L0
    ALOAD 0
    ALOAD 1
    CHECKCAST java/lang/String
    ALOAD 2
    CHECKCAST java/lang/String
    INVOKEVIRTUAL example/Example.compare (Ljava/lang/String;Ljava/lang/String;)I
    IRETURN
   L1
    LOCALVARIABLE first Ljava/lang/Object; L0 L1 1
    LOCALVARIABLE second Ljava/lang/Object; L0 L1 2
}
""", generate(def));
    }

    @Test
    void testGenericArrayBridge() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("toArray")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("values", TypeDef.variable("T").array())
                .returns(TypeDef.variable("T").array())
                .build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("toArray")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("values", TypeDef.of(String.class).array())
                .returns(TypeDef.of(String.class).array())
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/Parent<Ljava/lang/String;>;
// declaration: example/Example extends example.Parent<java.lang.String>
public class example/Example extends example/Parent {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/Parent.<init> ()V
    RETURN

  // access flags 0x1
  public toArray([Ljava/lang/String;)[Ljava/lang/String;
   L0
    ALOAD 1
    ARETURN
   L1
    LOCALVARIABLE values [Ljava/lang/String; L0 L1 1

  // access flags 0x1041
  public synthetic bridge toArray([Ljava/lang/Object;)[Ljava/lang/Object;
   L0
    ALOAD 0
    ALOAD 1
    CHECKCAST [Ljava/lang/String;
    INVOKEVIRTUAL example/Example.toArray ([Ljava/lang/String;)[Ljava/lang/String;
    CHECKCAST [Ljava/lang/Object;
    ARETURN
   L1
    LOCALVARIABLE values [Ljava/lang/Object; L0 L1 1
}
""", generate(def));
    }

    @Test
    void testAbstractOverrideGetsAnAbstractBridge() {
        ClassDef parent = ClassDef.builder("example.Top")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(TypeDef.variable("T")).build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("get")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .overrides()
                .returns(TypeDef.of(String.class))
                .build())
            .build();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x401
// signature Lexample/Top<Ljava/lang/String;>;
// declaration: example/Example extends example.Top<java.lang.String>
public abstract class example/Example extends example/Top {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/Top.<init> ()V
    RETURN

  // access flags 0x401
  public abstract get()Ljava/lang/String;

  // access flags 0x1441
  public abstract synthetic bridge get()Ljava/lang/Object;
}
""", generate(def));
    }

    @Test
    void testInterfaceRedeclarationGetsADefaultBridge() {
        InterfaceDef parent = InterfaceDef.builder("example.ParentInterface")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("get").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(TypeDef.variable("T")).build())
            .build();
        InterfaceDef def = InterfaceDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(parent), ClassTypeDef.of("example.Example")))
            .addMethod(MethodDef.builder("get")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .overrides()
                .returns(ClassTypeDef.of("example.Example"))
                .build())
            .build();

        assertEquals("""
// class version 61.0 (61)
// access flags 0x601
// signature Ljava/lang/Object;Lexample/ParentInterface<Lexample/Example;>;
// declaration: example/Example extends example.ParentInterface<example.Example>
public abstract interface example/Example implements example/ParentInterface {


  // access flags 0x401
  public abstract get()Lexample/Example;

  // access flags 0x1041
  public synthetic bridge default get()Ljava/lang/Object;
    ALOAD 0
    INVOKEINTERFACE example/Example.get ()Lexample/Example; (itf)
    ARETURN
}
""", generate(def));
    }

    @Test
    void testThreeLevelChain() {
        ClassDef a = ClassDef.builder("example.A")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC).returns(ClassTypeDef.of("example.A"))
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();
        ClassDef b = ClassDef.builder("example.B")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(a))
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC).overrides().returns(ClassTypeDef.of("example.B"))
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();
        ClassDef c = ClassDef.builder("example.C")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(b))
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC).overrides().returns(ClassTypeDef.of("example.C"))
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();

        // The middle bridges to the root
        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/A;
// declaration: example/B extends example.A
public class example/B extends example/A {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/A.<init> ()V
    RETURN

  // access flags 0x1
  public self()Lexample/B;
    ALOAD 0
    ARETURN

  // access flags 0x1041
  public synthetic bridge self()Lexample/A;
    ALOAD 0
    INVOKEVIRTUAL example/B.self ()Lexample/B;
    CHECKCAST example/A
    ARETURN
}
""", generate(b));

        // The leaf bridges to every ancestor erasure
        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/B;
// declaration: example/C extends example.B
public class example/C extends example/B {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/B.<init> ()V
    RETURN

  // access flags 0x1
  public self()Lexample/C;
    ALOAD 0
    ARETURN

  // access flags 0x1041
  public synthetic bridge self()Lexample/B;
    ALOAD 0
    INVOKEVIRTUAL example/C.self ()Lexample/C;
    CHECKCAST example/B
    ARETURN

  // access flags 0x1041
  public synthetic bridge self()Lexample/A;
    ALOAD 0
    INVOKEVIRTUAL example/C.self ()Lexample/C;
    CHECKCAST example/A
    ARETURN
}
""", generate(c));
    }

    @Test
    void testNoBridgeWhenTheErasureDoesNotDiffer() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("values")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.variable("T")))
                .build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("values")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(TypeDef.parameterized(ClassTypeDef.of(List.class), TypeDef.of(String.class)))
                .build((aThis, methodParameters) -> ClassTypeDef.of(ArrayList.class).instantiate().returning()))
            .build();

        // List<T> and List<String> erase to the same descriptor
        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/Parent<Ljava/lang/String;>;
// declaration: example/Example extends example.Parent<java.lang.String>
public class example/Example extends example/Parent {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/Parent.<init> ()V
    RETURN

  // access flags 0x1
  // signature ()Ljava/util/List<Ljava/lang/String;>;
  // declaration: java.util.List<java.lang.String> values()
  public values()Ljava/util/List;
    NEW java/util/ArrayList
    DUP
    INVOKESPECIAL java/util/ArrayList.<init> ()V
    ARETURN
}
""", generate(def));
    }

    @Test
    void testDuplicateInheritedSignatureYieldsOneBridge() {
        InterfaceDef id = InterfaceDef.builder("example.Id")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T")).build())
            .build();
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T")).build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(String.class)))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(id), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("id")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.of(String.class))
                .returns(TypeDef.of(String.class))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        // The same erasure is inherited through the superclass and the interface
        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/Parent<Ljava/lang/String;>;Lexample/Id<Ljava/lang/String;>;
// declaration: example/Example extends example.Parent<java.lang.String> implements example.Id<java.lang.String>
public class example/Example extends example/Parent implements example/Id {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/Parent.<init> ()V
    RETURN

  // access flags 0x1
  public id(Ljava/lang/String;)Ljava/lang/String;
   L0
    ALOAD 1
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/String; L0 L1 1

  // access flags 0x1041
  public synthetic bridge id(Ljava/lang/Object;)Ljava/lang/Object;
   L0
    ALOAD 0
    ALOAD 1
    CHECKCAST java/lang/String
    INVOKEVIRTUAL example/Example.id (Ljava/lang/String;)Ljava/lang/String;
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/Object; L0 L1 1
}
""", generate(def));
    }

    @Test
    void testBridgeRepeatsExceptionsAndAnnotations() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("handle")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T"))
                .returns(TypeDef.variable("T"))
                .addThrows(TypeDef.of(IOException.class))
                .build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("handle")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addAnnotation(Deprecated.class)
                .addThrows(TypeDef.of(IOException.class))
                .addParameter(ParameterDef.builder("value", TypeDef.of(String.class))
                    .addAnnotation(Deprecated.class)
                    .build())
                .returns(TypeDef.of(String.class))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        // The Java compiler repeats the Exceptions attribute and the annotations on the bridge
        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/Parent<Ljava/lang/String;>;
// declaration: example/Example extends example.Parent<java.lang.String>
public class example/Example extends example/Parent {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/Parent.<init> ()V
    RETURN

  // access flags 0x1
  public handle(Ljava/lang/String;)Ljava/lang/String; throws java/io/IOException
  @Ljava/lang/Deprecated;()
    // annotable parameter count: 1 (visible)
    @Ljava/lang/Deprecated;() // parameter 0
   L0
    ALOAD 1
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/String; L0 L1 1

  // access flags 0x1041
  public synthetic bridge handle(Ljava/lang/Object;)Ljava/lang/Object; throws java/io/IOException
  @Ljava/lang/Deprecated;()
    // annotable parameter count: 1 (visible)
    @Ljava/lang/Deprecated;() // parameter 0
   L0
    ALOAD 0
    ALOAD 1
    CHECKCAST java/lang/String
    INVOKEVIRTUAL example/Example.handle (Ljava/lang/String;)Ljava/lang/String;
    ARETURN
   L1
    LOCALVARIABLE value Ljava/lang/Object; L0 L1 1
}
""", generate(def));
    }

    @Test
    void testStaticMethodGetsNoBridge() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent))
            .addMethod(MethodDef.builder("self")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassTypeDef.of("example.Example"))
                .build((aThis, methodParameters) -> ClassTypeDef.of("example.Example").instantiate().returning()))
            .build();

        // A static method cannot override
        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/Parent;
// declaration: example/Example extends example.Parent
public class example/Example extends example/Parent {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/Parent.<init> ()V
    RETURN

  // access flags 0x9
  public static self()Lexample/Example;
    NEW example/Example
    DUP
    INVOKESPECIAL example/Example.<init> ()V
    ARETURN
}
""", generate(def));
    }

    @Test
    void testUnresolvedParentNameYieldsNoBridge() {
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of("example.UnknownParent"))
            .addMethod(MethodDef.builder("self")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(ClassTypeDef.of("example.Example"))
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();

        // A parent known only by name carries no method or generic metadata to derive bridges from
        assertEquals("""
// class version 61.0 (61)
// access flags 0x1
// signature Lexample/UnknownParent;
// declaration: example/Example extends example.UnknownParent
public class example/Example extends example/UnknownParent {


  // access flags 0x1
  public <init>()V
    ALOAD 0
    INVOKESPECIAL example/UnknownParent.<init> ()V
    RETURN

  // access flags 0x1
  public self()Lexample/Example;
    ALOAD 0
    ARETURN
}
""", generate(def));
    }

    @Test
    void testTwoMethodsSharingOneBridgeDescriptorBothGetTheirBridge() {
        InterfaceDef left = InterfaceDef.builder("example.Left")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("left").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T")).build())
            .build();
        InterfaceDef right = InterfaceDef.builder("example.Right")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("right").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T")).build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(left), TypeDef.of(String.class)))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(right), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("left")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.of(String.class))
                .returns(TypeDef.of(String.class))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .addMethod(MethodDef.builder("right")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.of(String.class))
                .returns(TypeDef.of(String.class))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        // The bridges share the descriptor but not the name, so both must be emitted
        assertEquals(
            List.of("left(Ljava/lang/Object;)Ljava/lang/Object;", "right(Ljava/lang/Object;)Ljava/lang/Object;"),
            bridgeMethodsOf(new ByteCodeWriter(false, false).write(def))
        );
    }

    @Test
    void testAReusedWriterEmitsTheBridgesEveryTime() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).returns(TypeDef.OBJECT).build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent))
            .addMethod(MethodDef.builder("self")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .returns(ClassTypeDef.of("example.Example"))
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();

        // The writer is reusable and must not remember the bridges of a previous emission
        ByteCodeWriter writer = new ByteCodeWriter(false, false);
        List<String> expected = List.of("self()Ljava/lang/Object;");
        assertEquals(expected, bridgeMethodsOf(writer.write(def)));
        assertEquals(expected, bridgeMethodsOf(writer.write(def)));
    }

    @Test
    void testAbstractOverrideWithErasedParameterGetsAnAbstractBridge() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T"))
                .returns(TypeDef.VOID)
                .build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .overrides()
                .addParameter("value", TypeDef.of(String.class))
                .returns(TypeDef.VOID)
                .build())
            .build();

        assertEquals(
            List.of("set(Ljava/lang/Object;)V (abstract)"),
            bridgeMethodsOf(new ByteCodeWriter(false, false).write(def))
        );
    }

    @Test
    void testInterfaceChainBridgesOnlyWhereTheErasureNarrows() {
        InterfaceDef top = InterfaceDef.builder("example.Top")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("A"))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("A")).returns(TypeDef.variable("A")).build())
            .build();
        // The middle redeclares with its own variable: same erasure, no bridge
        InterfaceDef middle = InterfaceDef.builder("example.Middle")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("B"))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(top), TypeDef.variable("B")))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("B")).returns(TypeDef.variable("B")).build())
            .build();
        // The leaf narrows the erasure: a concrete default bridge, like javac emits in an interface
        InterfaceDef leaf = InterfaceDef.builder("example.Leaf")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(middle), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.of(String.class)).returns(TypeDef.of(String.class)).build())
            .build();

        ByteCodeWriter writer = new ByteCodeWriter(false, false);
        assertEquals(List.of(), bridgeMethodsOf(writer.write(middle)));
        assertEquals(List.of("id(Ljava/lang/Object;)Ljava/lang/Object;"), bridgeMethodsOf(writer.write(leaf)));
    }

    @Test
    void testInterfaceDefaultMethodGetsADefaultBridge() {
        InterfaceDef top = InterfaceDef.builder("example.Top")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("A"))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("A")).returns(TypeDef.variable("A")).build())
            .build();
        InterfaceDef def = InterfaceDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(top), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("id")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.of(String.class))
                .returns(TypeDef.of(String.class))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        assertEquals(
            List.of("id(Ljava/lang/Object;)Ljava/lang/Object;"),
            bridgeMethodsOf(new ByteCodeWriter(false, false).write(def))
        );
    }

    @Test
    void testEnumImplementingAGenericInterface() {
        InterfaceDef id = InterfaceDef.builder("example.Id")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("id").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.variable("T")).build())
            .build();
        EnumDef def = EnumDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addEnumConstant("A")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(id), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("id")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.of(String.class))
                .returns(TypeDef.of(String.class))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        assertEquals(
            List.of("id(Ljava/lang/Object;)Ljava/lang/Object;"),
            bridgeMethodsOf(new ByteCodeWriter(false, false).write(def))
        );
    }

    @Test
    void testReflectedInterfaceInheritance() {
        // UnaryOperator declares nothing itself: apply is inherited from the reflected Function parent
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(UnaryOperator.class), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("apply")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.of(String.class))
                .returns(TypeDef.of(String.class))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        assertEquals(
            List.of("apply(Ljava/lang/Object;)Ljava/lang/Object;"),
            bridgeMethodsOf(new ByteCodeWriter(false, false).write(def))
        );
    }

    @Test
    void testGenericMethodsGetNoBridge() {
        // The type variables live on the methods, not the type: both erase identically
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addMethod(MethodDef.builder("pick")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addTypeVariable(TypeDef.variable("M"))
                .addParameter("value", TypeDef.variable("M"))
                .returns(TypeDef.variable("M"))
                .build())
            .addMethod(MethodDef.builder("pickBounded")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addTypeVariable(TypeDef.variable("M", TypeDef.of(Number.class)))
                .addParameter("value", TypeDef.variable("M", TypeDef.of(Number.class)))
                .returns(TypeDef.variable("M", TypeDef.of(Number.class)))
                .build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent))
            .addMethod(MethodDef.builder("pick")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addTypeVariable(TypeDef.variable("M"))
                .addParameter("value", TypeDef.variable("M"))
                .returns(TypeDef.variable("M"))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .addMethod(MethodDef.builder("pickBounded")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addTypeVariable(TypeDef.variable("M", TypeDef.of(Number.class)))
                .addParameter("value", TypeDef.variable("M", TypeDef.of(Number.class)))
                .returns(TypeDef.variable("M", TypeDef.of(Number.class)))
                .build((aThis, methodParameters) -> methodParameters.get(0).returning()))
            .build();

        assertEquals(List.of(), bridgeMethodsOf(new ByteCodeWriter(false, false).write(def)));
    }

    @Test
    void testMixedClassAndMethodGenerics() {
        // The class variable erases through the bridge, the method variable stays Object on both sides
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("convert")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addTypeVariable(TypeDef.variable("M"))
                .addParameter("input", TypeDef.variable("T"))
                .addParameter("seed", TypeDef.variable("M"))
                .returns(TypeDef.variable("M"))
                .build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("convert")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addTypeVariable(TypeDef.variable("M"))
                .addParameter("input", TypeDef.of(String.class))
                .addParameter("seed", TypeDef.variable("M"))
                .returns(TypeDef.variable("M"))
                .build((aThis, methodParameters) -> methodParameters.get(1).returning()))
            .build();

        assertEquals(
            List.of("convert(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            bridgeMethodsOf(new ByteCodeWriter(false, false).write(def))
        );
    }

    @Test
    void testOnlyTheMatchingOverloadGetsTheBridge() {
        InterfaceDef setter = InterfaceDef.builder("example.Setter")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("set").addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T")).returns(TypeDef.VOID).build())
            .build();
        ClassDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(setter), TypeDef.of(String.class)))
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC)
                .overrides()
                .addParameter("value", TypeDef.of(String.class))
                .returns(TypeDef.VOID)
                .build())
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.of(Integer.class))
                .returns(TypeDef.VOID)
                .build())
            .build();

        // The Integer overload does not implement Setter<String>.set, so only one bridge exists
        assertEquals(
            List.of("set(Ljava/lang/Object;)V"),
            bridgeMethodsOf(new ByteCodeWriter(false, false).write(def))
        );
    }

    private static List<String> bridgeMethodsOf(byte[] bytecode) {
        List<String> bridges = new ArrayList<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ((access & Opcodes.ACC_BRIDGE) != 0) {
                    bridges.add(name + descriptor + ((access & Opcodes.ACC_ABSTRACT) != 0 ? " (abstract)" : ""));
                }
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return bridges.stream().sorted().toList();
    }

    private String generate(ObjectDef objectDef) {
        StringWriter stringWriter = new StringWriter();
        var classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        var checkClassAdapter = new CheckClassAdapter(classWriter);
        var tcv = new TraceClassVisitor(checkClassAdapter, new PrintWriter(stringWriter));
        new ByteCodeWriter(false, false).writeObject(tcv, objectDef);
        tcv.visitEnd();
        classWriter.toByteArray();
        return stringWriter.toString();
    }
}
