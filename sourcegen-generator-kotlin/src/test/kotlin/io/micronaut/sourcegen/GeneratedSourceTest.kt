package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.StringWriter
import javax.lang.model.element.Modifier

class GeneratedSourceTest {
    @Test
    fun doesNotApplyATypeOnlyMarkerToEnumEntries() {
        val definition = EnumDef.builder("example.WithArguments")
            .addEnumConstant("ONE", ExpressionDef.constant(1))
            .addProperty(PropertyDef.builder("value").ofType(TypeDef.Primitive.INT).build())
            .addAllFieldsConstructor(Modifier.PRIVATE)
            .build()
        val source = write(definition)
        assertTrue(source.contains("ONE(1)"))
        assertEquals(1, markerCount(source))
    }

    @Test
    fun marksBackendCreatedCompanionObjects() {
        val definition = ClassDef.builder("example.WithStaticMember")
            .addField(FieldDef.builder("value", TypeDef.Primitive.INT)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC).build())
            .build()
        val source = write(definition)
        assertTrue(source.contains("companion object"))
        assertEquals(2, markerCount(source))
    }

    @Test
    fun automaticallyMarksAllTypeKinds() {
        for (definition in listOf(
            ClassDef.builder("example.MarkedClass").build(),
            RecordDef.builder("example.MarkedRecord").build(),
            EnumDef.builder("example.MarkedEnum").addEnumConstant("VALUE").build(),
            InterfaceDef.builder("example.MarkedInterface").build(),
            AnnotationObjectDef.builder("example.MarkedAnnotation").build()
        )) {
            assertEquals(1, markerCount(write(definition)))
            assertTrue(definition.annotations.isEmpty())
        }
    }

    @Test
    fun marksNestedTypes() {
        val definition = ClassDef.builder("example.Outer")
            .addInnerType(ClassDef.builder("Inner").build()).build()
        assertEquals(2, markerCount(write(definition)))
    }

    @Test
    fun doesNotDuplicateAnExplicitMarker() {
        val definition = ClassDef.builder("example.Explicit")
            .addAnnotation(AnnotationDef.builder(ClassTypeDef.of("io.micronaut.sourcegen.annotations.GeneratedSource")).build())
            .build()
        assertEquals(1, markerCount(write(definition)))
        assertEquals(1, definition.annotations.size)
    }

    private fun markerCount(source: String) = source.lineSequence().count { it.trim() == "@GeneratedSource" }

    private fun write(definition: ObjectDef): String = StringWriter().use { writer ->
        KotlinPoetSourceGenerator().write(definition, writer)
        writer.toString()
    }
}
