package io.micronaut.sourcegen.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overload resolution on wildcard receivers, mutually more specific variable arity methods, the least upper bound of
 * type variables, array names and supertypes whose dependencies are missing.
 */
class OverloadResolutionReviewTest {

    private static final ClassTypeDef BOX = ClassTypeDef.of(Box.class);
    private static final String P = "p";

    private static OverloadResolution.Resolution resolve(ClassTypeDef owner, String name, List<ExpressionDef> values) {
        return OverloadResolution.resolve(List.of(owner), name, TypeDef.STRING, values, null, null);
    }

    private static List<TypeDef> parameterTypes(MethodDef method) {
        return method.getParameters().stream().map(ParameterDef::getType).toList();
    }

    @Test
    void aLowerBoundedReceiverIsCapturedOnceForAllCandidates() {
        // javac picks p(T, T): both candidates see the same capture of `? super Integer`
        ClassTypeDef receiver = TypeDef.parameterized(BOX, TypeDef.wildcardSupertypeOf(ClassTypeDef.of(Integer.class)));

        OverloadResolution.Resolution resolution = resolve(receiver, P, List.of(ExpressionDef.constant(1), ExpressionDef.constant(2)));

        assertEquals(OverloadResolution.Outcome.RESOLVED, resolution.outcome());
        ExpressionDef.InvokeInstanceMethod call = new VariableDef.Local("b", receiver)
            .invoke(P, TypeDef.STRING, ExpressionDef.constant(1), ExpressionDef.constant(2));
        assertTrue(!TypeDef.OBJECT.equals(parameterTypes(call.method()).get(1)), parameterTypes(call.method()).toString());
    }

    @Test
    void anUpperBoundedReceiverIsCapturedOnceForAllCandidates() {
        ClassTypeDef receiver = TypeDef.parameterized(BOX, TypeDef.wildcardSubtypeOf(ClassTypeDef.of(Number.class)));

        OverloadResolution.Resolution resolution = resolve(receiver, P, List.of(ExpressionDef.nullValue(), ExpressionDef.nullValue()));

        assertEquals(OverloadResolution.Outcome.RESOLVED, resolution.outcome());
    }

    @Test
    void mutuallyMoreSpecificVariableArityMethodsAreAmbiguous() {
        // javac: "reference to m is ambiguous" for m(String...) and m(String, String...) with one argument
        OverloadResolution.Resolution resolution = resolve(ClassTypeDef.of(Varargs.class), "m", List.of(ExpressionDef.constant("a")));

        assertEquals(OverloadResolution.Outcome.AMBIGUOUS, resolution.outcome());
        assertEquals(2, resolution.ambiguous().size());
    }

    @Test
    void aVariableArityTailOfATypeVariablePacksTheBound() {
        // javac infers `all(n, 1)` with `N extends Number` as all(Number...) and packs a Number[]
        ExpressionDef number = new VariableDef.Local("n", TypeDef.variable("N", ClassTypeDef.of(Number.class)));

        ExpressionDef.InvokeStaticMethod call = ClassTypeDef.of(Varargs.class)
            .invokeStatic("all", TypeDef.STRING, number, ExpressionDef.constant(1));

        assertEquals(TypeDef.of(Number.class).array(), call.values().getFirst().type());
    }

    @Test
    void theErasedNameOfAnArrayLoadsTheArrayClass() {
        TypeLookup lookup = TypeLookup.reflective();
        String name = TypeHierarchy.erasedName(TypeDef.of(Number.class).array(2));

        assertSame(Number[][].class, lookup.loadClass(name));
        assertSame(int[].class, lookup.loadClass(TypeHierarchy.erasedName(TypeDef.Primitive.INT.array())));
        // Asked again, it is not recorded as missing
        assertSame(Number[][].class, lookup.loadClass(name));
    }

    @Test
    void aSupertypeWhoseDependencyIsMissingContributesNoMethods(@TempDir Path dir) throws Exception {
        Class<?> lib = compileWithoutDependency(dir);
        ClassDef sub = ClassDef.builder("example.Sub").superclass(ClassTypeDef.of(lib)).build();

        List<String> visited = new ArrayList<>();
        TypeHierarchy.visitInheritedMethods(sub, (TypeHierarchy.Lookup) null, (type, method) -> visited.add(method.name()));
        ExpressionDef.InvokeInstanceMethod call = new VariableDef.Local("s", sub.asTypeDef())
            .invoke("m", TypeDef.VOID, ExpressionDef.nullValue());

        assertTrue(visited.stream().noneMatch("m"::equals), visited.toString());
        assertEquals("m", call.method().getName());
        assertTrue(TypeHierarchy.superTypesOf(sub).contains(ClassTypeDef.of(lib)));
    }

    private static Class<?> compileWithoutDependency(Path dir) throws IOException, ClassNotFoundException {
        Path source = dir.resolve("src/example");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Dep.java"), "package example; public class Dep { }");
        Files.writeString(source.resolve("Lib.java"),
            "package example; public class Lib implements java.util.function.Consumer<Dep> { public void m(Dep d) { } public void accept(Dep d) { } }");
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int status = compiler.run(null, null, null, "-d", classes.toString(),
            source.resolve("Dep.java").toString(), source.resolve("Lib.java").toString());
        assertEquals(0, status);
        Files.delete(classes.resolve("example/Dep.class"));
        URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, OverloadResolutionReviewTest.class.getClassLoader());
        return Class.forName("example.Lib", false, loader);
    }

    @SuppressWarnings("unused")
    static class Box<T extends Number> {

        String p(T first, T second) {
            return "T, T";
        }

        String p(T first, Object second) {
            return "T, Object";
        }
    }

    @SuppressWarnings("unused")
    static class Varargs {

        static String m(String... values) {
            return "varargs";
        }

        static String m(String first, String... rest) {
            return "first and varargs";
        }

        @SafeVarargs
        static <T> String all(T... values) {
            return values.getClass().getComponentType().getName();
        }
    }
}
