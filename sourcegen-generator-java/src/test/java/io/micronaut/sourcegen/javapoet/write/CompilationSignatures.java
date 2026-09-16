package io.micronaut.sourcegen.javapoet.write;

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
}
