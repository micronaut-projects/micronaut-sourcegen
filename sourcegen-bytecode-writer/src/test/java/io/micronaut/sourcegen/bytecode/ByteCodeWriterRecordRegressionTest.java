package io.micronaut.sourcegen.bytecode;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.AnnotationObjectDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.ObjectDef;
import io.micronaut.sourcegen.model.PropertyDef;
import io.micronaut.sourcegen.model.RecordDef;
import io.micronaut.sourcegen.model.TypeDef;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.util.TraceClassVisitor;

import javax.lang.model.element.Modifier;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteCodeWriterRecordRegressionTest {

    @Test
    void preservesGenericArrayComponentSignatures() throws Exception {
        RecordDef recordDef = RecordDef.builder("example.ArrayRecord")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T"))
            .addProperty(PropertyDef.builder("values").ofType(TypeDef.variable("T").array()).build())
            .build();

        Class<?> generated = define(recordDef);

        assertInstanceOf(GenericArrayType.class, generated.getRecordComponents()[0].getGenericType());
        assertInstanceOf(GenericArrayType.class, generated.getDeclaredField("values").getGenericType());
        assertInstanceOf(GenericArrayType.class, generated.getMethod("values").getGenericReturnType());
        assertInstanceOf(GenericArrayType.class, generated.getDeclaredConstructors()[0].getGenericParameterTypes()[0]);
    }

    @Test
    void preservesWildcardComponentSignatures() {
        RecordDef recordDef = RecordDef.builder("example.WildcardRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("upper").ofType(TypeDef.parameterized(
                List.class, TypeDef.wildcardSubtypeOf(TypeDef.of(Number.class))
            )).build())
            .addProperty(PropertyDef.builder("lower").ofType(TypeDef.parameterized(
                List.class, TypeDef.wildcardSupertypeOf(TypeDef.of(Integer.class))
            )).build())
            .addProperty(PropertyDef.builder("any").ofType(TypeDef.parameterized(
                List.class, TypeDef.wildcard()
            )).build())
            .build();

        Class<?> generated = define(recordDef);
        WildcardType upper = wildcard(generated, 0);
        WildcardType lower = wildcard(generated, 1);
        WildcardType any = wildcard(generated, 2);

        assertEquals(List.of(Number.class), List.of(upper.getUpperBounds()));
        assertEquals(List.of(), List.of(upper.getLowerBounds()));
        assertEquals(List.of(Object.class), List.of(lower.getUpperBounds()));
        assertEquals(List.of(Integer.class), List.of(lower.getLowerBounds()));
        assertEquals(List.of(Object.class), List.of(any.getUpperBounds()));
        assertEquals(List.of(), List.of(any.getLowerBounds()));
    }

    @Test
    void preservesEveryTypeVariableBound() {
        RecordDef recordDef = RecordDef.builder("example.BoundedRecord")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", TypeDef.of(Number.class), TypeDef.of(Serializable.class)))
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.variable("T")).build())
            .build();

        TypeVariable<?> variable = define(recordDef).getTypeParameters()[0];

        assertEquals(List.of(Number.class, Serializable.class), List.of(variable.getBounds()));
    }

    @Test
    void writesCanonicalConstructorParameterNames() {
        RecordDef recordDef = RecordDef.builder("example.NamedRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("name").ofType(TypeDef.STRING).build())
            .build();

        var parameter = define(recordDef).getDeclaredConstructors()[0].getParameters()[0];

        assertTrue(parameter.isNamePresent());
        assertEquals("name", parameter.getName());
    }

    @Test
    void writesNestedAnnotationArraysOnMethods() throws Exception {
        AnnotationDef nested = AnnotationDef.builder(ClassTypeDef.of(Nested.class))
            .addMember("value", "kept")
            .build();
        RecordDef recordDef = methodAnnotatedRecord(
            "example.NestedAnnotatedRecord",
            AnnotationDef.builder(ClassTypeDef.of(Container.class))
                .addMember("value", new AnnotationDef[]{nested})
                .build()
        );

        Container annotation = define(recordDef).getMethod("annotated").getAnnotation(Container.class);

        assertEquals("kept", annotation.value()[0].value());
    }

    @Test
    void writesClassValuedAnnotationMembersOnMethods() throws Exception {
        RecordDef recordDef = methodAnnotatedRecord(
            "example.ClassAnnotatedRecord",
            AnnotationDef.builder(ClassTypeDef.of(ClassMember.class))
                .addMember("value", ClassTypeDef.of(String.class).getStaticField("class", TypeDef.CLASS))
                .build()
        );

        ClassMember annotation = define(recordDef).getMethod("annotated").getAnnotation(ClassMember.class);

        assertEquals(String.class, annotation.value());
    }

    @Test
    void honorsTypeAnnotationRetention() {
        AnnotationDef annotation = AnnotationDef.builder(ClassTypeDef.of(ClassRetentionTypeUse.class)).build();
        RecordDef recordDef = RecordDef.builder("example.RetentionRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.STRING.annotated(annotation)).build())
            .build();
        boolean[] seen = {false};
        boolean[] visible = {true};

        new ClassReader(new ByteCodeWriter().write(recordDef)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(
                            int typeRef, TypePath typePath, String annotationDescriptor, boolean runtimeVisible) {
                        seen[0] = true;
                        visible[0] = runtimeVisible;
                        return null;
                    }
                };
            }
        }, 0);

        assertTrue(seen[0]);
        assertFalse(visible[0]);
    }

    @Test
    void omitsSourceRetentionAnnotations() {
        RecordDef recordDef = RecordDef.builder("example.SourceAnnotatedRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.STRING)
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(SourceComponent.class)).build())
                .build())
            .build();

        assertFalse(trace(new ByteCodeWriter().write(recordDef)).contains(Type.getDescriptor(SourceComponent.class)));
    }

    @Test
    void honorsAnExplicitlyEmptyGeneratedTarget() {
        AnnotationObjectDef nowhere = AnnotationObjectDef.builder("example.Nowhere")
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Target.class))
                .addMember(AnnotationMetadata.VALUE_MEMBER, new Object[0])
                .build())
            .build();
        RecordDef recordDef = RecordDef.builder("example.TargetRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.STRING)
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(nowhere)).build())
                .build())
            .build();

        assertFalse(trace(new ByteCodeWriter().write(recordDef)).contains("Lexample/Nowhere;"));
    }

    @Test
    void keepsAnAnnotationWhoseGeneratedTargetIsNotRecognisable() {
        AnnotationObjectDef unrecognisable = AnnotationObjectDef.builder("example.Unrecognisable")
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(Target.class))
                // Nothing here names an ElementType, which leaves the annotation untargeted
                .addMember(AnnotationMetadata.VALUE_MEMBER, "java.lang.annotation.ElementType.FIELD")
                .build())
            .build();
        RecordDef recordDef = RecordDef.builder("example.UntargetedRecord")
            .addModifiers(Modifier.PUBLIC)
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.STRING)
                .addAnnotation(AnnotationDef.builder(ClassTypeDef.of(unrecognisable)).build())
                .build())
            .build();

        assertTrue(trace(new ByteCodeWriter().write(recordDef)).contains("Lexample/Unrecognisable;"));
    }

    @Test
    void writesAnInterfaceBoundNamedByStringInTheInterfacePosition() {
        RecordDef recordDef = RecordDef.builder("example.NamedBoundRecord")
            .addModifiers(Modifier.PUBLIC)
            .addTypeVariable(TypeDef.variable("T", ClassTypeDef.of(Serializable.class.getName())))
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.variable("T")).build())
            .build();

        assertTrue(
            trace(new ByteCodeWriter().write(recordDef)).contains("<T::Ljava/io/Serializable;>"),
            trace(new ByteCodeWriter().write(recordDef))
        );
    }

    @Test
    void writesAnInnerClassEntryForEveryNestMember() {
        RecordDef deep = RecordDef.builder("Deep").build();
        RecordDef inner = RecordDef.builder("Inner").addInnerType(deep).build();
        ClassDef outer = ClassDef.builder("example.Outer")
            .addModifiers(Modifier.PUBLIC)
            .addInnerType(inner)
            .build();

        String trace = trace(new ByteCodeWriter().write(outer));

        assertTrue(trace.contains("NESTMEMBER example/Outer$Inner$Deep"), trace);
        assertTrue(trace.contains("INNERCLASS example/Outer$Inner$Deep example/Outer$Inner Deep"), trace);
    }

    @Test
    void usesOneNestHostForEveryNestingLevel() throws Exception {
        RecordDef deep = RecordDef.builder("Deep").build();
        RecordDef inner = RecordDef.builder("Inner").addInnerType(deep).build();
        ClassDef outer = ClassDef.builder("example.Outer")
            .addModifiers(Modifier.PUBLIC)
            .addInnerType(inner)
            .build();
        ObjectDef emittedInner = outer.getInnerTypes().get(0);
        ObjectDef emittedDeep = emittedInner.getInnerTypes().get(0);
        Map<String, byte[]> definitions = new LinkedHashMap<>();
        ByteCodeWriter writer = new ByteCodeWriter();
        definitions.put(outer.getName(), writer.write(outer));
        definitions.put(emittedInner.getName(), writer.write(emittedInner, outer.asTypeDef()));
        definitions.put(emittedDeep.getName(), writer.write(emittedDeep, emittedInner.asTypeDef()));

        ClassLoader loader = new ClassLoader() {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = definitions.get(name);
                if (bytes == null) {
                    return super.findClass(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> outerClass = loader.loadClass("example.Outer");
        Class<?> innerClass = loader.loadClass("example.Outer$Inner");
        Class<?> deepClass = loader.loadClass("example.Outer$Inner$Deep");

        assertEquals(outerClass, innerClass.getNestHost());
        assertEquals(outerClass, deepClass.getNestHost());
        assertEquals(
            List.of("example.Outer", "example.Outer$Inner", "example.Outer$Inner$Deep"),
            Arrays.stream(outerClass.getNestMembers()).map(Class::getName).sorted().toList()
        );
    }

    private static WildcardType wildcard(Class<?> generated, int componentIndex) {
        ParameterizedType type = assertInstanceOf(
            ParameterizedType.class,
            generated.getRecordComponents()[componentIndex].getGenericType()
        );
        return assertInstanceOf(WildcardType.class, type.getActualTypeArguments()[0]);
    }

    private static RecordDef methodAnnotatedRecord(String name, AnnotationDef annotation) {
        return RecordDef.builder(name)
            .addModifiers(Modifier.PUBLIC)
            .addMethod(MethodDef.builder("annotated")
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeDef.STRING)
                .addAnnotation(annotation)
                .build((aThis, parameters) -> ExpressionDef.constant("ok").returning()))
            .build();
    }

    private static Class<?> define(RecordDef recordDef) {
        byte[] bytes = new ByteCodeWriter().write(recordDef);
        return new ClassLoader() {
            Class<?> define() {
                return defineClass(recordDef.getName(), bytes, 0, bytes.length);
            }
        }.define();
    }

    private static String trace(byte[] bytes) {
        StringWriter out = new StringWriter();
        new ClassReader(bytes).accept(new TraceClassVisitor(null, new PrintWriter(out)), 0);
        return out.toString();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Container {
        Nested[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Nested {
        String value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface ClassMember {
        Class<?> value();
    }

    @Retention(RetentionPolicy.CLASS)
    @Target(ElementType.TYPE_USE)
    @interface ClassRetentionTypeUse {
    }

    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.RECORD_COMPONENT)
    @interface SourceComponent {
    }
}
