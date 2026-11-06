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
package io.micronaut.sourcegen.model;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared type operations: unwrapping, raw types, erasure, substitution, the variables a type names, bound
 * chains and supertypes, and the lookup of classes by name.
 */
class TypeOperationsTest {

    private static final TypeDef.TypeVariable T = TypeDef.variable("T");
    private static final AnnotationDef ANNOTATION = AnnotationDef.builder(ClassTypeDef.of(Deprecated.class)).build();

    @Test
    void unwrapsAnnotationsAtAnyDepth() {
        TypeDef annotated = new TypeDef.AnnotatedTypeDef(new ClassTypeDef.AnnotatedClassTypeDef(TypeDef.STRING, List.of(ANNOTATION)),
            List.of(ANNOTATION));
        assertEquals(TypeDef.STRING, TypeOperations.unwrap(annotated));
        assertSame(T, TypeOperations.unwrap(T));
    }

    @Test
    void rawTypesDropTheTypeArguments() {
        ClassTypeDef outer = TypeDef.parameterized(ClassTypeDef.of("example.Outer"), TypeDef.STRING);
        ClassTypeDef member = ClassTypeDef.of("example.Outer$Member");
        ClassTypeDef memberOfParameterized = TypeDef.parameterized(TypeHierarchy.memberType(outer, member), TypeDef.of(Integer.class));

        assertEquals(ClassTypeDef.of(List.class), TypeOperations.rawClass(TypeDef.parameterized(List.class, String.class)));
        // The raw class keeps the enclosing type a member carries, the declared class does not
        assertEquals(TypeHierarchy.memberType(outer, member), TypeOperations.rawClass(memberOfParameterized));
        assertSame(member, TypeOperations.declaredClass(memberOfParameterized));

        assertEquals(ClassTypeDef.of(List.class), TypeOperations.raw(TypeDef.parameterized(List.class, String.class)));
        assertEquals(TypeDef.array(ClassTypeDef.of(List.class), 2),
            TypeOperations.raw(TypeDef.array(TypeDef.parameterized(List.class, String.class), 2)));
        assertSame(TypeDef.STRING, TypeOperations.raw(TypeDef.STRING));
    }

    @Test
    void aVariableErasesToItsLeftmostBound() {
        TypeDef.TypeVariable objectFirst = TypeDef.variable("T", TypeDef.OBJECT, TypeDef.parameterized(Comparable.class, T));
        TypeDef.TypeVariable numberFirst = TypeDef.variable("N", TypeDef.of(Number.class), TypeDef.of(Serializable.class));

        assertEquals(TypeDef.OBJECT, TypeOperations.erase(objectFirst));
        assertEquals(TypeDef.of(Number.class), TypeOperations.erase(numberFirst));
        assertEquals(TypeDef.OBJECT, TypeOperations.erase(T));
        // A bound of a parameterized type is its class
        assertEquals(ClassTypeDef.of(List.class), TypeOperations.erase(TypeDef.variable("L", TypeDef.parameterized(List.class, String.class))));
    }

    @Test
    void anArrayKeepsItsRank() {
        TypeDef.TypeVariable number = TypeDef.variable("N", TypeDef.of(Number.class));

        assertEquals(TypeDef.array(TypeDef.of(Number.class), 2), TypeOperations.erase(TypeDef.array(number, 2)));
        assertEquals(TypeDef.array(ClassTypeDef.of(List.class), 3),
            TypeOperations.erase(TypeDef.array(TypeDef.parameterized(List.class, String.class), 3)));
    }

    @Test
    void wildcardsAndParameterizationsErase() {
        assertEquals(TypeDef.of(Number.class), TypeOperations.erase(TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class))));
        assertEquals(TypeDef.OBJECT, TypeOperations.erase(TypeDef.wildcardSupertypeOf(TypeDef.of(Number.class))));
        assertEquals(ClassTypeDef.of(Map.class), TypeOperations.erase(TypeDef.parameterized(Map.class, String.class, Integer.class)));
        assertEquals(TypeDef.of(Number.class), TypeOperations.erase(TypeDef.variable("W", TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class)))));
    }

    @Test
    void aVariableBoundedByItselfErasesToObject() {
        Map<String, TypeDef> bounds = Map.of("A", TypeDef.variable("B"), "B", TypeDef.variable("A"));

        assertEquals(TypeDef.OBJECT, TypeOperations.erase(TypeDef.variable("A"), bounds::get, null));
        assertEquals(TypeDef.OBJECT, TypeOperations.erase(TypeDef.variable("A", TypeDef.variable("A")), name -> null, null));
    }

    @Test
    void theMethodScopeShadowsTheClassScope() {
        List<TypeDef.TypeVariable> methodVariables = List.of(TypeDef.variable("T", TypeDef.of(Number.class)));
        List<TypeDef.TypeVariable> typeVariables = List.of(TypeDef.variable("T", TypeDef.STRING), TypeDef.variable("U", TypeDef.of(Long.class)));

        assertEquals(TypeDef.of(Number.class), TypeOperations.erase(T, methodVariables, typeVariables));
        assertEquals(TypeDef.of(Long.class), TypeOperations.erase(TypeDef.variable("U"), methodVariables, typeVariables));
        assertEquals(TypeDef.OBJECT, TypeOperations.erase(TypeDef.variable("V"), methodVariables, typeVariables));
        assertEquals(List.of(TypeDef.of(Number.class)), TypeOperations.declaredBounds("T", methodVariables, typeVariables));
        // A method variable declared without bounds shadows the class variable of the name all the same
        assertEquals(List.of(), TypeOperations.declaredBounds("T", List.of(T), typeVariables));
    }

    @Test
    void thisIsErasedToTheTypeItStandsFor() {
        ClassTypeDef self = ClassTypeDef.of("example.Self");

        assertEquals(self, TypeOperations.erase(TypeDef.THIS, name -> null, self));
        assertEquals(TypeDef.THIS, TypeOperations.erase(TypeDef.THIS));
    }

    @Test
    void aReflectiveTypeErasesToItsClass() throws NoSuchMethodException {
        Method method = Generic.class.getDeclaredMethod("first", List.class);

        assertEquals(List.class, TypeOperations.erase(method.getTypeParameters()[0]));
        assertEquals(Number.class, TypeOperations.erase(method.getTypeParameters()[1]));
        assertEquals(List.class, TypeOperations.erase(method.getGenericParameterTypes()[0]));
        assertEquals(Object.class, TypeOperations.erase(Generic.class.getTypeParameters()[0]));
    }

    @Test
    void theTypeVariablesOfADefinition() {
        ClassDef classDef = ClassDef.builder("example.Box").addTypeVariable(T).build();

        assertEquals(List.of(T), TypeOperations.typeVariablesOf(classDef));
        assertEquals(List.of(), TypeOperations.typeVariablesOf(null));
        assertEquals(List.of(), TypeOperations.typeVariablesOf(EnumDef.builder("example.Kind").addEnumConstant("ONE").build()));
    }

    @Test
    void substitutesAtAnyDepth() {
        Map<String, TypeDef> substitution = Map.of("T", TypeDef.STRING);
        ClassTypeDef outer = TypeDef.parameterized(ClassTypeDef.of("example.Outer"), T);
        ClassTypeDef member = ClassTypeDef.of("example.Outer$Member");

        assertEquals(TypeDef.parameterized(Map.class, TypeDef.STRING, TypeDef.parameterized(List.class, TypeDef.STRING)),
            TypeOperations.substitute(TypeDef.parameterized(ClassTypeDef.of(Map.class), T, TypeDef.parameterized(ClassTypeDef.of(List.class), T)), substitution));
        assertEquals(TypeDef.array(TypeDef.STRING, 2), TypeOperations.substitute(TypeDef.array(T, 2), substitution));
        assertEquals(TypeDef.wildcardSubtypeOf(TypeDef.STRING), TypeOperations.substitute(TypeDef.wildcardSubtypeOf(T), substitution));
        assertEquals(TypeHierarchy.memberType(TypeDef.parameterized(ClassTypeDef.of("example.Outer"), TypeDef.STRING), member),
            TypeOperations.substitute(TypeHierarchy.memberType(outer, member), substitution));
        // A nullable variable is substituted by the nullable replacement
        assertTrue(TypeOperations.substitute(T.makeNullable(), substitution).isNullable());
        assertEquals(TypeDef.variable("U"), TypeOperations.substitute(TypeDef.variable("U"), substitution));
    }

    @Test
    void walksTheVariablesATypeNames() {
        ClassTypeDef member = TypeHierarchy.memberType(TypeDef.parameterized(ClassTypeDef.of("example.Outer"), TypeDef.variable("X")),
            ClassTypeDef.of("example.Outer$Member"));
        TypeDef type = TypeDef.parameterized(ClassTypeDef.of(Map.class), TypeDef.wildcardSupertypeOf(T),
            TypeDef.array(TypeDef.parameterized(member, TypeDef.variable("U")), 1));

        assertEquals(List.of("T", "X", "U"), new ArrayList<>(TypeOperations.variablesOf(type)));
        assertTrue(TypeOperations.mentionsVariable(type, "X"::equals));
        assertFalse(TypeOperations.mentionsVariable(type, "V"::equals));
        assertTrue(TypeOperations.containsVariableOtherThan(type, Set.of("T", "U")));
        assertFalse(TypeOperations.containsVariableOtherThan(type, Set.of("T", "U", "X")));
        assertFalse(TypeOperations.mentionsVariable(TypeDef.STRING, name -> true));
    }

    @Test
    void expandsAChainOfBounds() {
        TypeDef.TypeVariable v = TypeDef.variable("V", TypeDef.of(Number.class), TypeDef.of(Serializable.class));
        TypeDef.TypeVariable u = TypeDef.variable("U", TypeDef.variable("V"), TypeDef.parameterized(Comparable.class, TypeDef.variable("U")));
        Map<String, TypeDef.TypeVariable> declared = Map.of("U", u, "V", v);

        assertEquals(List.of(TypeDef.of(Number.class), TypeDef.of(Serializable.class), TypeDef.parameterized(Comparable.class, TypeDef.variable("U"))),
            TypeOperations.expandBounds(TypeDef.variable("U"), reference -> declared.get(reference.name())));
        // A variable that is not expanded is a bound itself
        assertEquals(List.of(TypeDef.variable("V"), TypeDef.parameterized(Comparable.class, TypeDef.variable("U"))),
            TypeOperations.expandBounds(u, reference -> reference.name().equals("U") ? u : null));
        assertEquals(List.of(), TypeOperations.expandBounds(TypeDef.variable("W"), reference -> null));
    }

    @Test
    void aCycleOfBoundsEnds() {
        Map<String, TypeDef.TypeVariable> declared = Map.of(
            "A", TypeDef.variable("A", TypeDef.variable("B")),
            "B", TypeDef.variable("B", TypeDef.variable("A"), TypeDef.STRING));

        assertEquals(List.of(TypeDef.STRING), TypeOperations.expandBounds(TypeDef.variable("A"), reference -> declared.get(reference.name())));
    }

    @Test
    void aTypeAsItsSupertype() {
        ClassTypeDef list = TypeDef.parameterized(ArrayList.class, String.class);

        assertEquals(TypeDef.parameterized(Collection.class, String.class), TypeOperations.asSupertype(list, Collection.class.getName(), null));
        // A raw type inherits its supertypes raw
        assertEquals(ClassTypeDef.of(Collection.class), TypeOperations.asSupertype(ClassTypeDef.of(ArrayList.class), Collection.class.getName(), null));
        assertEquals(TypeDef.OBJECT, TypeOperations.asSupertype(list, Object.class.getName(), null));
        assertNull(TypeOperations.asSupertype(list, Map.class.getName(), null));
    }

    @Test
    void looksUpClassesWithoutInitializingThem() {
        TypeLookup lookup = TypeLookup.reflective();

        assertEquals(int.class, lookup.loadClass("int"));
        assertEquals(String.class, lookup.loadClass(String.class.getName()));
        assertEquals(NotInitialized.class, lookup.loadClass(NotInitialized.class.getName()));
        assertFalse(Initialization.observed);
        assertNull(lookup.loadClass("example.Missing"));
        // A miss is remembered, and asked again all the same
        assertNull(lookup.loadClass("example.Missing"));
        assertNull(lookup.classElement(String.class.getName()));
        assertEquals(ClassTypeDef.of(String.class), lookup.find(String.class.getName()));
        assertNull(lookup.find("example.Missing"));
    }

    @Test
    void loadsTheClassOfAType() {
        TypeLookup lookup = TypeLookup.reflective();

        assertEquals(String[][].class, lookup.loadClass(TypeDef.array(TypeDef.STRING, 2)));
        assertEquals(int[].class, lookup.loadClass(TypeDef.array(TypeDef.Primitive.INT)));
        assertEquals(List.class, lookup.loadClass(TypeDef.parameterized(List.class, String.class)));
        assertEquals(List.class, lookup.loadClass(ClassTypeDef.of(List.class.getName())));
        assertNull(lookup.loadClass(T));
        assertNull(lookup.loadClass(ClassTypeDef.of("example.Missing")));
    }

    /**
     * A generic class with a generic method.
     *
     * @param <E> A variable
     */
    abstract static class Generic<E> {
        abstract <L extends List<String>, N extends Number> N first(List<N> values);
    }

    /**
     * A class whose initialization is observed.
     */
    static final class NotInitialized {
        static {
            Initialization.observed = true;
        }
    }

    /**
     * Whether {@link NotInitialized} is initialized.
     */
    static final class Initialization {
        static boolean observed;
    }
}
