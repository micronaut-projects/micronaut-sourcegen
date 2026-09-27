package io.micronaut.sourcegen.javapoet.write;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.TypeHierarchy;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A member class of a parameterized enclosing type is written with the enclosing type's arguments.
 */
public class MemberTypeWriteTest extends AbstractWriteTest {

    @Test
    void memberTypesKeepTheEnclosingTypeArguments() throws Exception {
        ClassDef classDef = ClassDef.builder("test.MemberTypes").addModifiers(Modifier.PUBLIC)
            .addMethod(returning("member"))
            .addMethod(returning("plainMember"))
            .addMethod(returning("memberArray"))
            .addMethod(returning("nestedMember"))
            .build();

        String source = writeClass(classDef);

        assertEquals("""
            package test;

            import io.micronaut.sourcegen.javapoet.write.MemberTypeWriteTest;
            import java.lang.Integer;
            import java.lang.Long;
            import java.lang.String;

            public class MemberTypes {
              public MemberTypeWriteTest.Outer<String>.Member<Integer> member() {
                return null;
              }

              public MemberTypeWriteTest.Outer<String>.PlainMember plainMember() {
                return null;
              }

              public MemberTypeWriteTest.Outer<String>.Member<Integer>[] memberArray() {
                return null;
              }

              public MemberTypeWriteTest.Outer<String>.Member<Integer>.Nested<Long> nestedMember() {
                return null;
              }
            }
            """, source);
        JavaCompileAssertions.assertCompiles(source);
    }

    private static MethodDef returning(String name) throws IOException {
        try {
            return MethodDef.builder(name).addModifiers(Modifier.PUBLIC)
                .returns(TypeHierarchy.typeDefOf(Signatures.class.getMethod(name).getGenericReturnType()))
                .build((self, parameters) -> ExpressionDef.nullValue().returning());
        } catch (NoSuchMethodException e) {
            throw new IOException(e);
        }
    }

    /**
     * A generic enclosing type.
     *
     * @param <T> The enclosing variable
     */
    @SuppressWarnings("ClassCanBeStatic")
    public static class Outer<T> {
        /**
         * A generic member.
         *
         * @param <U> The member variable
         */
        public class Member<U> {
            /**
             * A member of a member.
             *
             * @param <V> The variable
             */
            public class Nested<V> {
            }
        }

        /**
         * A member declaring no variables.
         */
        public class PlainMember {
        }
    }

    /**
     * The signatures javac gives the member types.
     */
    public interface Signatures {
        Outer<String>.Member<Integer> member();

        Outer<String>.PlainMember plainMember();

        Outer<String>.Member<Integer>[] memberArray();

        Outer<String>.Member<Integer>.Nested<Long> nestedMember();
    }
}
