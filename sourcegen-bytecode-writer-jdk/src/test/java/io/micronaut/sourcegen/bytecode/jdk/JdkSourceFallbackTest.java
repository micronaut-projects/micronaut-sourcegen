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
package io.micronaut.sourcegen.bytecode.jdk;

import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.FieldDef;
import io.micronaut.sourcegen.model.InterfaceDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.lang.classfile.ClassFile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java source fallback, used for definitions the direct writer declines. Every definition here
 * carries a switch yield case, which is the construct that sends it down that path.
 */
class JdkSourceFallbackTest {

    private static MethodDef declinedMethod(String name) {
        return MethodDef.builder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter("index", TypeDef.Primitive.INT)
            .returns(TypeDef.STRING)
            .build((ignored, parameters) -> parameters.get(0).asExpressionSwitch(TypeDef.STRING,
                Map.of(ExpressionDef.constant(1), new ExpressionDef.SwitchYieldCase(TypeDef.STRING,
                    ExpressionDef.constant("one").returning())),
                ExpressionDef.constant("other")
            ).returning());
    }

    @Test
    void compilesTheTypesADeclinedDefinitionReferences() throws Exception {
        // Each of these is reachable only through the definition, so the fallback has to render
        // and compile them alongside it
        ClassDef referencedField = ClassDef.builder("example.FallbackField")
            .addModifiers(Modifier.PUBLIC)
            .build();
        InterfaceDef referencedInterface = InterfaceDef.builder("example.FallbackContract")
            .addModifiers(Modifier.PUBLIC)
            .build();
        ClassDef referencedReturn = ClassDef.builder("example.FallbackReturn")
            .addModifiers(Modifier.PUBLIC)
            .build();
        ClassDef referencedParameter = ClassDef.builder("example.FallbackParameter")
            .addModifiers(Modifier.PUBLIC)
            .build();

        ClassDef definition = ClassDef.builder("example.FallbackRoot")
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(referencedInterface.asTypeDef())
            .addField(FieldDef.builder("field", referencedField.asTypeDef())
                .addModifiers(Modifier.PUBLIC).build())
            // A parameterized, an array and an annotated type all have to be walked through
            .addField(FieldDef.builder("names", TypeDef.parameterized(List.class, String.class))
                .addModifiers(Modifier.PUBLIC).build())
            .addField(FieldDef.builder("grid", new TypeDef.Array(TypeDef.Primitive.INT, 2, false))
                .addModifiers(Modifier.PUBLIC).build())
            .addField(FieldDef.builder("counter", TypeDef.Primitive.INT)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .initializer(ExpressionDef.constant(3))
                .build())
            .addMethod(declinedMethod("describe"))
            .addMethod(MethodDef.builder("make")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter("input", referencedParameter.asTypeDef())
                .returns(referencedReturn.asTypeDef())
                .addThrows(TypeDef.of(java.io.IOException.class))
                .build((ignored, parameters) -> referencedReturn.asTypeDef().instantiate().returning()))
            .build();

        Map<String, byte[]> produced = new ByteCodeWriter().writeAll(definition);

        // Only the requested definition comes back; the types it referenced were compiled
        // alongside it so that its own source could resolve them
        assertEquals(List.of("example.FallbackRoot"), List.copyOf(produced.keySet()));
        assertTrue(ClassFile.of().verify(produced.get(definition.getName())).isEmpty());
    }

    @Test
    void runsTheClassTheFallbackCompiled() throws Exception {
        ClassDef definition = ClassDef.builder("example.FallbackStandalone")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("counter", TypeDef.Primitive.INT)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .initializer(ExpressionDef.constant(3))
                .build())
            .addMethod(declinedMethod("describe"))
            .build();

        Map<String, byte[]> produced = new ByteCodeWriter().writeAll(definition);
        Class<?> generated = new MapClassLoader(produced).loadClass(definition.getName());

        assertEquals("one", generated.getMethod("describe", int.class).invoke(null, 1));
        assertEquals("other", generated.getMethod("describe", int.class).invoke(null, 2));
        assertEquals(3, generated.getField("counter").get(null));
    }

    @Test
    void compilesAMemberTypeInsideTheDefinitionThatDeclaresIt() throws Exception {
        ClassDef member = ClassDef.builder("Member")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(declinedMethod("describe"))
            .build();
        ClassDef outer = ClassDef.builder("example.FallbackOuter")
            .addModifiers(Modifier.PUBLIC)
            .addField(FieldDef.builder("shared", TypeDef.STRING)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .initializer(ExpressionDef.constant("outer"))
                .build())
            .addInnerType(member)
            .build();
        ClassDef declared = (ClassDef) outer.getInnerTypes().get(0);

        // Writing the member on its own compiles it inside its real enclosing definition
        Map<String, byte[]> produced = new ByteCodeWriter().writeAll(declared, outer.asTypeDef());

        assertEquals(List.of(declared.getName()), List.copyOf(produced.keySet()));
        assertNotNull(produced.get(declared.getName()));
        assertTrue(ClassFile.of().verify(produced.get(declared.getName())).isEmpty());
    }

    @Test
    void compilesAMemberTypeAgainstAnOuterNameWhenNoDefinitionIsAvailable() throws Exception {
        // Only the outer type's name is known here, so the fallback compiles the member inside an
        // otherwise empty stub of it
        ClassDef member = ClassDef.builder("example.FallbackStubOuter$Member")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(declinedMethod("describe"))
            .build();

        Map<String, byte[]> produced =
            new ByteCodeWriter().writeAll(member, ClassTypeDef.of("example.FallbackStubOuter"));

        assertEquals(List.of(member.getName()), List.copyOf(produced.keySet()));
        assertTrue(ClassFile.of().verify(produced.get(member.getName())).isEmpty());
    }

    @Test
    void compilesAMemberOfAnInterface() throws Exception {
        ClassDef member = ClassDef.builder("example.FallbackInterfaceOuter$Member")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addMethod(declinedMethod("describe"))
            .build();
        InterfaceDef outer = InterfaceDef.builder("example.FallbackInterfaceOuter")
            .addModifiers(Modifier.PUBLIC)
            .build();

        Map<String, byte[]> produced = new ByteCodeWriter().writeAll(member, outer.asTypeDef());

        assertTrue(ClassFile.of().verify(produced.get(member.getName())).isEmpty());
    }

    @Test
    void reportsWhatTheCompilerSaidWhenTheSourceCannotCompile() {
        // A method body that names a type which does not exist anywhere
        ClassDef definition = ClassDef.builder("example.FallbackBroken")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(declinedMethod("describe"))
            .addMethod(MethodDef.builder("broken")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeDef.OBJECT)
                .build((ignored, parameters) -> StatementDef.multi(
                    ClassTypeDef.of("example.definitely.Missing").instantiate().returning()
                )))
            .build();

        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> new ByteCodeWriter().writeAll(definition));
        assertTrue(e.getMessage().contains("JDK compilation of generated source failed"), e.getMessage());
    }

    private static final class MapClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private MapClassLoader(Map<String, byte[]> classes) {
            super(JdkSourceFallbackTest.class.getClassLoader());
            this.classes = new LinkedHashMap<>(classes);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                return super.findClass(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
