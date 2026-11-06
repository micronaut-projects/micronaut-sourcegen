package io.micronaut.sourcegen.javapoet.write;

/**
 * A class that inherits a generic method from its superclass and from an interface, which generated sources call.
 */
public final class DeclarationOrderFixtures {

    private DeclarationOrderFixtures() {
    }

    public static class HierarchyBase<B> {
        public String accept(B value) {
            return "base:" + value;
        }
    }

    public interface HierarchyApi<A> {
        String accept(A value);
    }

    public static class HierarchyImpl extends HierarchyBase<String> implements HierarchyApi<String> {
        @Override
        public String accept(String value) {
            return "impl:" + value;
        }
    }
}
