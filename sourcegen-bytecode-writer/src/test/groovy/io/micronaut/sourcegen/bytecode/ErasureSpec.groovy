package io.micronaut.sourcegen.bytecode

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.ast.ClassElement
import io.micronaut.inject.ast.ElementQuery
import io.micronaut.inject.ast.MethodElement
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.util.CheckClassAdapter

import javax.lang.model.element.Modifier

/**
 * The erasure of a generic signature, taken from a javac {@link ClassElement}, rendered as class literals
 * and read back from the decompiled class.
 */
class ErasureSpec extends AbstractTypeElementSpec {

    void "type variables and wildcards erase to their bounds"() {
        given:
        ClassElement element = buildClassElement('''
package test;

import java.util.List;
import java.util.Optional;

interface Repository {
    <T extends Number, U> T pick(T value, U unbounded, List<? extends Number> numbers,
                                 List<? super Integer> sink, T[] values, Optional<T> optional);
}
''')
        MethodElement pick = element.getEnclosedElement(ElementQuery.ALL_METHODS.named("pick")).orElseThrow()

        List<TypeDef> erasures = []
        for (parameter in pick.parameters) {
            ClassElement type = parameter.genericType
            erasures.add(TypeDef.erasure(type))
            type.firstTypeArgument.ifPresent { erasures.add(TypeDef.erasure(it)) }
        }
        erasures.add(TypeDef.erasure(pick.genericReturnType))

        ClassTypeDef classType = ClassTypeDef.of(Class)
        ClassDef classDef = ClassDef.builder("example.Erasures")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("ofPick")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(classType.array())
                .build((t, params) -> classType.array()
                    .instantiate(erasures.collect { new ExpressionDef.Constant(classType, it) })
                    .returning()))
            .build()

        when:
        def classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)
        new ByteCodeWriter(false, false).writeObject(new CheckClassAdapter(classWriter), classDef)

        then:
        DecompilerUtils.decompileToJava(classWriter.toByteArray()) == '''package example;

import java.util.List;
import java.util.Optional;

public class Erasures {
   public static Class[] ofPick() {
      return new Class[]{Number.class, Object.class, List.class, Number.class, List.class, Object.class, Number[].class, Optional.class, Number.class, Number.class};
   }
}
'''
    }
}
