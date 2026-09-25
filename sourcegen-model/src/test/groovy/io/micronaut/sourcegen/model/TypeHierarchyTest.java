package io.micronaut.sourcegen.model;

import io.micronaut.inject.ast.ClassElement;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Walks of {@link TypeHierarchy}: supertypes and the substitution of their type arguments, erasure of type variables,
 * member types and the shapes of hierarchies - diamonds, cycles, wildcards, renamed variables.
 */
class TypeHierarchyTest {

    @Test
    void walksPastAnElementReportingTypeArgumentsInPlaceOfItsPlaceholders() {
        // The Groovy element of a parameterized type reports its type arguments in place of its placeholders
        ClassElement argument = element("java.lang.String", List.of());
        ClassElement supplier = element("example.StringSupplier", List.of(argument));
        ClassDef classDef = ClassDef.builder("example.Supplied")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of("example.StringSupplier"), TypeDef.STRING))
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .build();
        List<String> lookedUp = new ArrayList<>();
        List<String> visited = new ArrayList<>();

        TypeHierarchy.visitInheritedMethods(classDef, name -> {
            lookedUp.add(name);
            return name.equals(supplier.getName()) ? supplier : null;
        }, (type, method) -> {
            visited.add(type.getName() + "#" + method.name());
            return true;
        });

        assertEquals(List.of("example.StringSupplier"), lookedUp);
        assertEquals(List.of("java.util.function.Supplier#get"), visited);
    }

    @Test
    void substitutesTheTypeArgumentsOfASupertype() {
        ClassDef classDef = ClassDef.builder("example.Supplied")
            .addSuperinterface(TypeDef.parameterized(Supplier.class, String.class))
            .build();
        List<TypeDef> returnTypes = new ArrayList<>();

        TypeHierarchy.visitInheritedMethods(classDef, null, (type, method) -> {
            returnTypes.add(type.substitute(method.genericReturnType()));
            return true;
        });

        assertEquals(List.of(TypeDef.STRING), returnTypes);
    }

    @Test
    void erasesATypeVariableToItsLeftmostBound() {
        // JLS 4.6: `N extends Object & Comparable<N>` erases to Object, the descriptor the bytecode writers give it
        TypeDef.TypeVariable variable = new TypeDef.TypeVariable("N", List.of(TypeDef.OBJECT,
            TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("N"))), false);
        TypeDef.TypeVariable number = new TypeDef.TypeVariable("M", List.of(TypeDef.of(Number.class),
            TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("M"))), false);
        ClassDef classDef = ClassDef.builder("example.Numbers").addTypeVariable(variable).addTypeVariable(number).build();
        TypeHierarchy.InheritedType declaring = TypeHierarchy.declaring(classDef);

        assertEquals(TypeDef.OBJECT, declaring.erase(variable));
        assertEquals(TypeDef.OBJECT, declaring.erase(TypeDef.variable("N")));
        assertEquals(TypeDef.of(Number.class), declaring.erase(TypeDef.variable("M")));
    }

    @Test
    void namesAMemberTypeReferencedByItsSimpleName() {
        ClassDef outer = ClassDef.builder("example.Outer").addInnerType(ClassDef.builder("Key").build()).build();

        assertEquals("example.Outer$Key", TypeHierarchy.erasedName(ClassTypeDef.of("Key"), outer));
        assertEquals("example.Outer$Key[]", TypeHierarchy.erasedName(ClassTypeDef.of("Key").array(), outer));
        assertEquals("Key", TypeHierarchy.erasedName(ClassTypeDef.of("Key")));
    }

    @Test
    void substitutionKeepsTheNullabilityOfAnArrayAndOfAVariable() {
        TypeDef nullableArray = TypeDef.array(TypeDef.variable("T")).makeNullable();
        TypeDef nullableVariable = TypeDef.variable("T").makeNullable();

        assertTrue(TypeHierarchy.substituted(nullableArray, Map.of("T", TypeDef.STRING)).isNullable());
        assertTrue(TypeHierarchy.substituted(nullableVariable, Map.of("T", TypeDef.STRING)).isNullable());
        assertEquals(TypeDef.STRING, TypeHierarchy.substituted(TypeDef.variable("T"), Map.of("T", TypeDef.STRING)));
    }

    /**
     * {@code Base<T extends Number>} declares {@code <T> void m(T)}: the {@code T} of the method is its own,
     * unbounded, and erases to {@code Object} - which is what the bytecode writer's descriptor has
     * ({@code TypeUtils}). The walk reports the parameter as the name-only variable, and {@code erase} takes the
     * bound of the class's {@code T} of the same name: {@code Number}. The bridge resolver then adds a bridge
     * {@code m(Number)} (see the bridge tests of the bytecode writer).
     */
    @Test
    void methodVariableShadowingAClassVariableErasesToItsOwnBound() {
        TypeDef.TypeVariable methodT = TypeDef.variable("T");
        ClassDef base = ClassDef.builder("test.Base").addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class)))
            .addMethod(MethodDef.builder("m").addTypeVariable(methodT).addParameter("value", methodT).build())
            .build();
        ClassDef sub = ClassDef.builder("test.Sub")
            .superclass(TypeDef.parameterized(ClassTypeDef.of(base), TypeDef.of(Integer.class)))
            .build();
        List<TypeDef> erased = new ArrayList<>();
        TypeHierarchy.visitInheritedMethods(sub, null, (type, method) -> {
            erased.add(type.erase(method.bridgeParameters().get(0)));
            return true;
        });

        assertEquals(List.of(TypeDef.OBJECT), erased);
    }

    /**
     * {@code T extends Object & Comparable<T>} erases to its leftmost bound, {@code Object} (JLS 4.6), which is the
     * descriptor the bytecode writer's {@code TypeUtils} writes. TypeHierarchy takes the first bound that is not
     * Object, {@code Comparable}, so bridges and override matching disagree with the descriptor the writer gives the
     * declaration (see the bridge tests of the bytecode writer, and {@code OverrideResolutionRegressionTest} of the
     * Java generator).
     */
    @Test
    void objectLedIntersectionErasesToItsLeftmostBound() {
        TypeDef.TypeVariable t = TypeDef.variable("T", TypeDef.OBJECT,
            TypeDef.parameterized(ClassTypeDef.of(Comparable.class), TypeDef.variable("T")));
        ClassDef classDef = ClassDef.builder("test.Ordered").addTypeVariable(t).build();

        assertEquals(TypeDef.OBJECT, TypeHierarchy.declaring(classDef).erase(TypeDef.variable("T")));
    }

    /**
     * {@code Outer<X>.Inner<Y>} names {@code X} through its enclosing type. {@code containsVariableOtherThan} looks
     * into type arguments, arrays and wildcards, but not into the enclosing type of a {@code MemberOf}, so it answers
     * that the type names no variable - and OverrideResolver returns {@code Outer<X (method)>.Member} as a resolved
     * signature (see {@code OverrideResolutionRegressionTest} of the Java generator).
     */
    @Test
    void aMemberOfAParameterizedEnclosingTypeNamesTheVariablesOfIt() {
        ClassTypeDef member = TypeHierarchy.memberType(
            TypeDef.parameterized(ClassTypeDef.of(Outer.class), TypeDef.variable("X")), ClassTypeDef.of(Outer.Inner.class));

        assertTrue(TypeHierarchy.containsVariableOtherThan(member, Set.of()));
        assertTrue(TypeHierarchy.containsVariableOtherThan(TypeDef.parameterized(member, TypeDef.STRING), Set.of()));
    }

    /**
     * A member type that names a sibling member type by the definition the caller holds - still named by its simple
     * name, as {@code addInnerType} qualifies only the copy it stores. The package of the supertype is read from that
     * name, the default package, so its package-private methods are taken for ones of another package (see
     * {@code OverrideResolutionRegressionTest#packagePrivateMethodOfASiblingMemberTypeNamedByItsSimpleName}).
     */
    @Test
    void aSiblingMemberTypeNamedBySimpleNameIsOfThePackageOfItsOutermostType() {
        ClassDef base = ClassDef.builder("Base").addTypeVariable(TypeDef.variable("T"))
            .addMethod(MethodDef.builder("make").returns(TypeDef.variable("T")).build())
            .build();
        ClassDef impl = ClassDef.builder("Impl")
            .superclass(TypeDef.parameterized(ClassTypeDef.of(base), TypeDef.STRING))
            .build();
        ClassDef outer = ClassDef.builder("test.Siblings").addInnerType(base).addInnerType(impl).build();
        ObjectDef storedImpl = outer.getInnerTypes().get(1);
        List<String> packages = new ArrayList<>();
        TypeHierarchy.visitInheritedMethods(storedImpl, null, (type, method) -> {
            packages.add(type.getPackageName());
            return true;
        });

        assertEquals("test", TypeHierarchy.packageOf(storedImpl));
        assertEquals(List.of("test"), packages);
    }

    /**
     * Variables whose bounds name each other - {@code <A extends B, B extends A>} - are no Java, but the model can
     * express them, and the bytecode writer's {@code TypeUtils} erases them to {@code Object} by the variables it has
     * visited. {@code TypeHierarchy.erase} follows the bounds without such a guard, and overflows the stack.
     */
    @Test
    void cyclicVariableBoundsErase() {
        ClassDef classDef = ClassDef.builder("test.Cyclic")
            .addTypeVariable(TypeDef.variable("A", TypeDef.variable("B")))
            .addTypeVariable(TypeDef.variable("B", TypeDef.variable("A")))
            .build();

        assertEquals(TypeDef.OBJECT, assertTimeoutPreemptively(Duration.ofSeconds(10),
            () -> TypeHierarchy.declaring(classDef).erase(TypeDef.variable("A"))));
    }

    /**
     * A static nested class of a generic class is of no parameterization of it, where an inner class is: reflection
     * converts the first to a plain parameterized type and the second to a {@code MemberOf}.
     */
    @Test
    void staticNestedTypesDoNotCarryTheEnclosingTypeArguments() throws Exception {
        TypeDef inner = TypeHierarchy.typeDefOf(Outer.class.getMethod("inner").getGenericReturnType());
        TypeDef nested = TypeHierarchy.typeDefOf(Outer.class.getMethod("nested").getGenericReturnType());

        ClassTypeDef.Parameterized innerType = assertInstanceOf(ClassTypeDef.Parameterized.class, inner);
        assertNotNull(TypeHierarchy.enclosingOf(innerType.rawType()));
        ClassTypeDef.Parameterized nestedType = assertInstanceOf(ClassTypeDef.Parameterized.class, nested);
        assertNull(TypeHierarchy.enclosingOf(nestedType.rawType()));
        assertEquals(Map.of("T", TypeDef.STRING), TypeHierarchy.enclosingArguments(innerType, null));
        assertEquals(Map.of(), TypeHierarchy.enclosingArguments(nestedType, null));
    }

    /**
     * A diamond: {@code Impl extends DirectSource implements StringSource}, both reaching {@code Source<String>}. The
     * interface is visited once, with {@code T} bound to {@code String}.
     */
    @Test
    void diamondReachingOneParameterizationTwiceVisitsItOnce() {
        ClassDef impl = ClassDef.builder("test.Diamond")
            .superclass(ClassTypeDef.of(DirectSource.class))
            .addSuperinterface(ClassTypeDef.of(StringSource.class))
            .build();
        List<TypeDef> returns = new ArrayList<>();
        TypeHierarchy.visitInheritedMethods(impl, null, (type, method) -> {
            if (method.name().equals("get")) {
                returns.add(type.substitute(method.genericReturnType()));
            }
            return true;
        });

        assertEquals(List.of(TypeDef.STRING), returns);
    }

    /**
     * A wildcard as the type argument of a supertype - {@code Supplier<? extends Number>} - is no Java, but the walk
     * does not fail on it.
     */
    @Test
    void wildcardSupertypeArgumentDoesNotFailTheWalk() {
        ClassDef classDef = ClassDef.builder("test.WildcardSupertype")
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class))))
            .build();
        List<TypeDef> erased = new ArrayList<>();
        TypeHierarchy.visitInheritedMethods(classDef, null, (type, method) -> {
            erased.add(type.erase(type.substitute(method.genericReturnType())));
            return true;
        });

        assertEquals(List.of(TypeDef.of(Number.class)), erased);
        assertNotNull(TypeHierarchy.asSupertype(classDef.asTypeDef(), Supplier.class.getName(), null));
    }

    /**
     * {@code class B<X> extends A<List<X>>}, {@code A<T> implements Supplier<T>}, and {@code C extends B<String>}:
     * each edge substitutes once, and {@code asSupertype} answers {@code Supplier<List<String>>}.
     */
    @Test
    void renamedVariablesAreSubstitutedOncePerEdge() {
        TypeDef.TypeVariable t = TypeDef.variable("T");
        TypeDef.TypeVariable x = TypeDef.variable("X");
        ClassDef a = ClassDef.builder("test.A").addTypeVariable(t)
            .addSuperinterface(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), t)).build();
        ClassDef b = ClassDef.builder("test.B").addTypeVariable(x)
            .superclass(TypeDef.parameterized(ClassTypeDef.of(a), TypeDef.parameterized(ClassTypeDef.of(List.class), x))).build();
        ClassDef c = ClassDef.builder("test.C")
            .superclass(TypeDef.parameterized(ClassTypeDef.of(b), TypeDef.STRING)).build();

        assertEquals(TypeDef.parameterized(ClassTypeDef.of(Supplier.class), TypeDef.parameterized(List.class, String.class)),
            TypeHierarchy.asSupertype(c.asTypeDef(), Supplier.class.getName(), null));
    }

    private static ClassElement element(String name, List<?> placeholders) {
        return (ClassElement) Proxy.newProxyInstance(ClassElement.class.getClassLoader(), new Class<?>[]{ClassElement.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName", "toString" -> name;
                case "getDeclaredGenericPlaceholders" -> placeholders;
                case "getEnclosedElements", "getInterfaces" -> List.of();
                case "getSuperType" -> Optional.empty();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    /** A generic class with an inner class and a static nested class. */
    public static class Outer<T> {
        /** An inner class: it is of a parameterization of {@code Outer}. */
        public class Inner<U> {
        }

        /** A static nested class: it is of no parameterization of {@code Outer}. */
        public static class Nested<U> {
        }

        public Outer<String>.Inner<Integer> inner() {
            return null;
        }

        public Nested<Integer> nested() {
            return null;
        }
    }

    /** One interface, reached twice. */
    public interface Source<T> {
        T get();
    }

    /** The interface through another. */
    public interface StringSource extends Source<String> {
    }

    /** The interface directly. */
    public abstract static class DirectSource implements Source<String> {
    }
}
