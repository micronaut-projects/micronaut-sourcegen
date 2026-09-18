package io.micronaut.sourcegen.model;

import io.micronaut.inject.ast.ClassElement;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void erasesATypeVariableToItsFirstBoundThatIsNotObject() {
        // A variable can list Object ahead of its real bound; the JVM descriptor takes the real one
        TypeDef.TypeVariable variable = new TypeDef.TypeVariable("N", List.of(TypeDef.OBJECT, TypeDef.of(Number.class)), false);
        ClassDef classDef = ClassDef.builder("example.Numbers").addTypeVariable(variable).build();
        TypeHierarchy.InheritedType declaring = TypeHierarchy.declaring(classDef);

        assertEquals(TypeDef.of(Number.class), declaring.erase(variable));
        assertEquals(TypeDef.of(Number.class), declaring.erase(TypeDef.variable("N")));
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
}
