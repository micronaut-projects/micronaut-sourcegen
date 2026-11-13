package io.micronaut.sourcegen.javapoet.write;

import java.util.ArrayList;
import java.util.List;

/**
 * Methods with generic signatures that the sources of {@link JavaSourceCompilationTest} call, which the generator
 * resolves to decide on the conversions of their arguments.
 */
public final class CompilationSignatures {

    private CompilationSignatures() {
    }

    public static int sum(List<Number> numbers) {
        return numbers.size();
    }

    public static int firstSize(List<? extends List<String>> lists) {
        return lists.get(0).size();
    }

    public static String join(String[] parts, List<Number> numbers) {
        return String.join(",", parts) + numbers;
    }

    public static <T> List<T> flatten(List<? extends List<T>> lists) {
        return lists.get(0);
    }

    public static int addTo(List<? super List<String>> target) {
        return target.size();
    }

    /**
     * A superclass whose constructor takes varargs.
     */
    public static class VarargsParent {

        public VarargsParent(Object... values) {
        }
    }

    /**
     * A concrete list, which declares no type variables of its own.
     */
    public static final class StringList extends ArrayList<String> {
    }

    /**
     * A generic method whose own variable is bounded, next to the class's.
     *
     * @param <T> The value type
     */
    public static class BoundedEcho<T> {

        public <U extends Number> T echo(T value, U other) {
            return value;
        }
    }
}
