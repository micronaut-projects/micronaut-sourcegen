/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.sourcegen.bytecode.core;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeResolverTest {

    @Test
    void resolvesNoBridgesForMethodsThatCannotBeOverridden() {
        MethodDef constructor = MethodDef.constructor().addModifiers(Modifier.PUBLIC).build();
        MethodDef staticMethod = MethodDef.builder("of")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC).returns(TypeDef.STRING).build();
        MethodDef privateMethod = MethodDef.builder("hidden")
            .addModifiers(Modifier.PRIVATE).returns(TypeDef.STRING).build();
        ClassDef classDef = ClassDef.builder("example.Simple")
            .addMethod(constructor).addMethod(staticMethod).addMethod(privateMethod)
            .build();

        assertTrue(BridgeResolver.resolve(classDef, constructor).isEmpty());
        assertTrue(BridgeResolver.resolve(classDef, staticMethod).isEmpty());
        assertTrue(BridgeResolver.resolve(classDef, privateMethod).isEmpty());
        // Without a declaring definition there is no hierarchy to search
        assertTrue(BridgeResolver.resolve(null, staticMethod).isEmpty());
    }

    @Test
    void resolvesABridgeForAnErasedParameter() {
        TypeDef.TypeVariable variable = TypeDef.variable("T");
        InterfaceDef consumer = InterfaceDef.builder("example.Consumer")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("accept")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", variable)
                .build())
            .build();
        MethodDef implementation = MethodDef.builder("accept")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.STRING)
            .build();
        ClassDef classDef = ClassDef.builder("example.StringConsumer")
            .addSuperinterface(new ClassTypeDef.Parameterized(consumer.asTypeDef(), List.of(TypeDef.STRING)))
            .addMethod(implementation)
            .build();

        List<BridgeResolver.BridgeMethod> bridges = BridgeResolver.resolve(classDef, implementation);

        assertEquals(1, bridges.size());
        assertEquals(List.of(TypeDef.OBJECT), bridges.get(0).parameterTypes());
        assertEquals(TypeDef.VOID, bridges.get(0).returnType());
    }

    @Test
    void resolvesABridgeThroughAClassSuperTypeAndAJavaInterface() {
        // A supertype from the model
        TypeDef.TypeVariable variable = TypeDef.variable("T");
        ClassDef parent = ClassDef.builder("example.Parent")
            .addTypeVariable(variable)
            .addMethod(MethodDef.builder("value")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(variable)
                .build())
            .build();
        MethodDef implementation = MethodDef.builder("value")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.STRING)
            .build();
        ClassDef child = ClassDef.builder("example.Child")
            .superclass(new ClassTypeDef.Parameterized(parent.asTypeDef(), List.of(TypeDef.STRING)))
            .addMethod(implementation)
            .build();
        List<BridgeResolver.BridgeMethod> inherited = BridgeResolver.resolve(child, implementation);
        assertEquals(1, inherited.size());
        assertTrue(inherited.get(0).parameterTypes().isEmpty());
        assertEquals(TypeDef.OBJECT, inherited.get(0).returnType());

        // A supertype loaded from the class path
        MethodDef apply = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.STRING)
            .returns(TypeDef.STRING)
            .build();
        ClassDef function = ClassDef.builder("example.StringFunction")
            .addSuperinterface(TypeDef.parameterized(Function.class, String.class, String.class))
            .addMethod(apply)
            .build();

        List<BridgeResolver.BridgeMethod> bridges = BridgeResolver.resolve(function, apply);

        assertEquals(1, bridges.size());
        assertEquals(List.of(TypeDef.OBJECT), bridges.get(0).parameterTypes());
        assertEquals(TypeDef.OBJECT, bridges.get(0).returnType());
    }

    @Test
    void resolvesNoBridgeWhenTheErasureAlreadyMatches() {
        MethodDef apply = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.OBJECT)
            .build();
        ClassDef function = ClassDef.builder("example.ObjectFunction")
            .addSuperinterface(TypeDef.parameterized(Function.class, Object.class, Object.class))
            .addMethod(apply)
            .build();

        assertTrue(BridgeResolver.resolve(function, apply).isEmpty());
    }
    @Test
    void multipleTypeVariables() {
        InterfaceDef parent = InterfaceDef.builder("example.Mapper")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("I"))
            .addTypeVariable(TypeDef.variable("O", TypeDef.of(Number.class)))
            .addMethod(MethodDef.builder("map")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("input", TypeDef.variable("I"))
                .returns(TypeDef.variable("O"))
                .build())
            .build();
        MethodDef method = MethodDef.builder("map")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("input", TypeDef.of(String.class))
            .returns(TypeDef.of(Integer.class))
            .build((aThis, methodParameters) -> methodParameters.get(0).invoke("length", TypeDef.Primitive.INT).cast(TypeDef.of(Integer.class)).returning());
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(String.class), TypeDef.of(Integer.class)))
            .addMethod(method)
            .build();

        // I erases to Object, O to its Number bound
        assertEquals(List.of("(Ljava/lang/Object;)Ljava/lang/Number;"), descriptors(def, method));
    }

    @Test
    void nestedParameterizedSupertypes() {
        InterfaceDef top = InterfaceDef.builder("example.Top")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("A"))
            .addMethod(MethodDef.builder("id")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("A"))
                .returns(TypeDef.variable("A"))
                .build())
            .build();
        InterfaceDef middle = InterfaceDef.builder("example.Middle")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("B", TypeDef.of(CharSequence.class)))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(top), TypeDef.variable("B")))
            .addMethod(MethodDef.builder("id")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("B"))
                .returns(TypeDef.variable("B"))
                .build())
            .build();
        MethodDef method = MethodDef.builder("id")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.of(String.class))
            .returns(TypeDef.of(String.class))
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(middle), TypeDef.of(String.class)))
            .addMethod(method)
            .build();

        // The substitution composes across the edges: B -> String in Middle, A -> B -> String in Top
        assertEquals(
            List.of("(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;", "(Ljava/lang/Object;)Ljava/lang/Object;"),
            descriptors(def, method)
        );
    }

    @Test
    void wildcardTypeArgumentDoesNotMatchAConcreteOverride() {
        InterfaceDef parent = InterfaceDef.builder("example.Consumer")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("accept")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T"))
                .returns(TypeDef.VOID)
                .build())
            .build();
        MethodDef method = MethodDef.builder("accept")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.of(Number.class))
            .returns(TypeDef.VOID)
            .build();
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class))))
            .addMethod(method)
            .build();

        // `? extends Number` erases to Number, matching the declared parameter, and the declaration
        // erases to Object
        assertEquals(List.of("(Ljava/lang/Object;)V"), descriptors(def, method));
    }

    @Test
    void inheritedThroughAnIntermediateWithoutARedeclaration() {
        InterfaceDef top = InterfaceDef.builder("example.Top")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("get")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.variable("T"))
                .build())
            .build();
        // The middle interface neither redeclares the method nor rebinds the variable
        InterfaceDef middle = InterfaceDef.builder("example.Middle")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(top), TypeDef.variable("T")))
            .build();
        MethodDef method = MethodDef.builder("get")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.of(String.class))
            .build((aThis, methodParameters) -> ExpressionDefs.constant().returning());
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(middle), TypeDef.of(String.class)))
            .addMethod(method)
            .build();

        assertEquals(List.of("()Ljava/lang/Object;"), descriptors(def, method));
    }

    @Test
    void reflectedDeepHierarchy() {
        // BiFunction is reached through reflection; apply(T, U) -> R erases to three Objects
        MethodDef method = MethodDef.builder("apply")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("first", TypeDef.of(String.class))
            .addParameter("second", TypeDef.of(String.class))
            .returns(TypeDef.of(String.class))
            .build((aThis, methodParameters) -> methodParameters.get(0).returning());
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(BiFunction.class), TypeDef.of(String.class), TypeDef.of(String.class), TypeDef.of(String.class)))
            .addMethod(method)
            .build();

        assertEquals(List.of("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), descriptors(def, method));
    }

    @Test
    void constructorsAndStaticAndPrivateMethodsResolveNothing() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC).returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();
        MethodDef constructor = MethodDef.constructor().build();
        MethodDef staticMethod = MethodDef.builder("self")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ClassTypeDef.of("example.Example"))
            .build((aThis, methodParameters) -> ClassTypeDef.of("example.Example").instantiate().returning());
        MethodDef privateMethod = MethodDef.builder("self")
            .addModifiers(Modifier.PRIVATE)
            .returns(ClassTypeDef.of("example.Example"))
            .build((aThis, methodParameters) -> aThis.returning());
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent))
            .addMethod(constructor)
            .addMethod(staticMethod)
            .addMethod(privateMethod)
            .build();

        assertEquals(List.of(), descriptors(def, constructor));
        assertEquals(List.of(), descriptors(def, staticMethod));
        assertEquals(List.of(), descriptors(def, privateMethod));
    }

    @Test
    void unresolvedStringParentResolvesNothing() {
        MethodDef method = MethodDef.builder("self")
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassTypeDef.of("example.Example"))
            .build((aThis, methodParameters) -> aThis.returning());
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of("example.UnknownParent"))
            .addMethod(method)
            .build();

        assertEquals(List.of(), descriptors(def, method));
    }

    @Test
    void overloadDeclaredByTheClassIsNotBridged() {
        InterfaceDef parent = InterfaceDef.builder("example.Setter")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("T"))
                .returns(TypeDef.VOID)
                .build())
            .build();
        MethodDef method = MethodDef.builder("set")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.of(String.class))
            .returns(TypeDef.VOID)
            .build();
        // The class itself declares the erased overload, so no bridge may be generated for it
        MethodDef erasedOverload = MethodDef.builder("set")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.OBJECT)
            .returns(TypeDef.VOID)
            .build();
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(parent), TypeDef.of(String.class)))
            .addMethod(method)
            .addMethod(erasedOverload)
            .build();

        assertEquals(List.of(), descriptors(def, method));
    }

    @Test
    void declaringTypeBoundSurvivesAnIntermediateSupertype() {
        InterfaceDef top = InterfaceDef.builder("example.Top")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("A"))
            .addMethod(MethodDef.builder("set")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameter("value", TypeDef.variable("A"))
                .returns(TypeDef.VOID)
                .build())
            .addMethod(MethodDef.builder("get")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(TypeDef.variable("A"))
                .build())
            .build();
        InterfaceDef middle = InterfaceDef.builder("example.Middle")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("B"))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(top), TypeDef.variable("B")))
            .build();
        // The declaring type binds its own variable, which the ancestors know nothing about
        MethodDef setter = MethodDef.builder("set")
            .addModifiers(Modifier.PUBLIC)
            .addParameter("value", TypeDef.variable("T"))
            .returns(TypeDef.VOID)
            .build();
        MethodDef getter = MethodDef.builder("get")
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeDef.variable("T"))
            .build((aThis, methodParameters) -> ExpressionDefs.constant().cast(TypeDef.variable("T")).returning());
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(middle), TypeDef.variable("T")))
            .addMethod(setter)
            .addMethod(getter)
            .build();

        // T erases to Number where the method is declared, the bridge to Top's Object
        assertEquals(List.of("(Ljava/lang/Object;)V"), descriptors(def, setter));
        assertEquals(List.of("()Ljava/lang/Object;"), descriptors(def, getter));
    }

    @Test
    void finalParentMethodIsNotOverridable() {
        ClassDef parent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("self").addModifiers(Modifier.PUBLIC, Modifier.FINAL).returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();
        MethodDef method = MethodDef.builder("self")
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassTypeDef.of("example.Example"))
            .build((aThis, methodParameters) -> aThis.returning());
        ObjectDef def = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(parent))
            .addMethod(method)
            .build();

        assertEquals(List.of(), descriptors(def, method));
    }

    @Test
    void packagePrivateParentMethodIsOnlyOverridableFromItsOwnPackage() {
        ClassDef samePackageParent = ClassDef.builder("example.Parent")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("self").returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();
        ClassDef otherPackageParent = ClassDef.builder("other.Parent")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("self").returns(TypeDef.OBJECT)
                .build((aThis, methodParameters) -> aThis.returning()))
            .build();
        MethodDef method = MethodDef.builder("self")
            .addModifiers(Modifier.PUBLIC)
            .returns(ClassTypeDef.of("example.Example"))
            .build((aThis, methodParameters) -> aThis.returning());

        ObjectDef samePackage = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(samePackageParent))
            .addMethod(method)
            .build();
        assertEquals(List.of("()Ljava/lang/Object;"), descriptors(samePackage, method));

        ObjectDef otherPackage = ClassDef.builder("example.Example")
            .addModifiers(Modifier.PUBLIC)
            .superclass(ClassTypeDef.of(otherPackageParent))
            .addMethod(method)
            .build();
        assertEquals(List.of(), descriptors(otherPackage, method));
    }

    /**
     * The bridge descriptors in a stable order, so the expectations read as JVM descriptors
     * rather than as model objects.
     */
    private static List<String> descriptors(ObjectDef objectDef, MethodDef methodDef) {
        return BridgeResolver.resolve(objectDef, methodDef).stream()
            .map(bridge -> bridge.parameterTypes().stream()
                .map(t -> TypeUtils.getDescriptor(t, null))
                .reduce("", String::concat)
                .transform(parameters -> "(" + parameters + ")" + TypeUtils.getDescriptor(bridge.returnType(), null)))
            .sorted()
            .toList();
    }

    private static final class ExpressionDefs {

        private ExpressionDefs() {
        }

        static ExpressionDef constant() {
            return ExpressionDef.constant("value");
        }
    }
}
