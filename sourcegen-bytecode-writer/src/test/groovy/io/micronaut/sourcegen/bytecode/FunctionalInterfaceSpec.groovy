package io.micronaut.sourcegen.bytecode

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.ast.ClassElement
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.util.CheckClassAdapter

import javax.lang.model.element.Modifier

/**
 * A lambda over a functional interface, taken from a javac {@link ClassElement}, that redeclares a
 * public method of {@link Object} as abstract.
 */
class FunctionalInterfaceSpec extends AbstractTypeElementSpec {

    void "a redeclared Object method does not count as a second abstract method"() {
        given:
        // Comparator redeclares equals(Object); this interface redeclares all three
        ClassElement element = buildClassElement('''
package test;

interface Checker<T> {
    boolean check(T value);

    @Override
    boolean equals(Object other);

    @Override
    int hashCode();

    @Override
    String toString();
}
''')
        ClassTypeDef checkerType = ClassTypeDef.of(element)
        VariableDef.Local checker = new VariableDef.Local("checker", TypeDef.of(element).resolveTypeVariables(["T": TypeDef.STRING]))

        ClassDef classDef = ClassDef.builder("example.Checks")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("isEmpty")
                .addModifiers(Modifier.PUBLIC)
                .addParameter("value", TypeDef.STRING)
                .returns(TypeDef.Primitive.BOOLEAN)
                .build((t, params) -> StatementDef.multi(
                    checker.defineAndAssign(checkerType.getLambda(["T": TypeDef.STRING])
                        .implement((lt, lp) -> lp.get(0).invoke("isEmpty", TypeDef.Primitive.BOOLEAN).returning())),
                    checker.invoke("check", [TypeDef.OBJECT], TypeDef.Primitive.BOOLEAN, [params.get(0)]).returning()
                )))
            .build()

        when:
        def classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)
        new ByteCodeWriter(false, false).writeObject(new CheckClassAdapter(classWriter), classDef)

        then:
        DecompilerUtils.decompileToJava(classWriter.toByteArray()) == '''package example;

import test.Checker;

public class Checks {
   public boolean isEmpty(String value) {
      Checker checker = Checks::lambda$isEmpty$0;
      return checker.check(value);
   }

   private static boolean lambda$isEmpty$0(String value) {
      return value.isEmpty();
   }
}
'''
    }
}
