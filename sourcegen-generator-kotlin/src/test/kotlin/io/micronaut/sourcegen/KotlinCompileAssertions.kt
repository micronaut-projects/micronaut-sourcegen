package io.micronaut.sourcegen

import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.ObjectDef
import io.micronaut.sourcegen.model.StatementDef
import io.micronaut.sourcegen.model.TypeDef
import io.micronaut.sourcegen.model.VariableDef
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import java.io.StringWriter
import java.nio.file.Files
import java.net.URLClassLoader
import javax.lang.model.element.Modifier

/**
 * Renders definitions with the Kotlin generator and compiles the sources, so that a test can assert the output is
 * not only well formed but also valid Kotlin, and run it. The helpers the test classes share live here.
 */
object KotlinCompileAssertions {

    /** Renders a definition with the Kotlin generator. */
    fun render(definition: ObjectDef): String =
        StringWriter().also { KotlinPoetSourceGenerator().write(definition, it) }.toString()

    /** Renders a definition and asserts the source is the expected one. */
    fun assertSource(definition: ObjectDef, expected: String): String {
        val source = render(definition)
        assertEquals(expected, source)
        return source
    }

    /** Renders the definitions and compiles them together. */
    fun compile(vararg definitions: ObjectDef): URLClassLoader =
        compileAndLoad(*definitions.map { render(it) }.toTypedArray())

    /**
     * Renders the definitions, asserts each source is the snapshot `<directory>/<simple name>.txt` of the test
     * resources, and compiles them together.
     */
    fun compileMatchingSnapshots(directory: String, vararg definitions: ObjectDef): URLClassLoader {
        val sources = definitions.map { definition ->
            val source = render(definition)
            val resource = "$directory/${definition.simpleName}.txt"
            javaClass.getResourceAsStream(resource).use { expected ->
                assertNotNull(expected, "$resource\n$source")
                assertEquals(expected!!.bufferedReader(Charsets.UTF_8).readText(), source, definition.name)
            }
            source
        }
        return compileAndLoad(*sources.toTypedArray())
    }

    /**
     * Compiles a public class `name` with the single public method `run`, whose parameters are named `p0`, `p1`...,
     * and invokes it on a new instance with the arguments.
     */
    fun runMethod(
        name: String,
        returns: TypeDef,
        parameters: List<TypeDef>,
        vararg arguments: Any?,
        body: (VariableDef.This, List<VariableDef.MethodParameter>) -> StatementDef
    ): Any? {
        val method = MethodDef.builder("run").addModifiers(Modifier.PUBLIC).returns(returns)
        parameters.forEachIndexed { i, type -> method.addParameter("p$i", type) }
        val def = ClassDef.builder(name).addModifiers(Modifier.PUBLIC).addMethod(method.build(body)).build()
        return invokeRun(def, *arguments)
    }

    /**
     * What a method does, as the differential test compares it: the value it returns, or the class of the exception it
     * throws.
     */
    fun outcomeOf(action: () -> Any?): Any? = try {
        action()
    } catch (e: java.lang.reflect.InvocationTargetException) {
        e.targetException.javaClass
    }

    /** Compiles a definition and invokes its method `run` on a new instance with the arguments. */
    fun invokeRun(definition: ClassDef, vararg arguments: Any?): Any? {
        compile(definition).use { loader ->
            val cls = loader.loadClass(definition.name)
            val run = cls.methods.first { it.name == "run" }
            return run.invoke(cls.getConstructor().newInstance(), *arguments)
        }
    }

    /** Creates an instance of a compiled class through its no-argument constructor. */
    fun newInstance(loader: ClassLoader, definition: ObjectDef): Any =
        loader.loadClass(definition.name).getDeclaredConstructor().newInstance()

    /**
     * Renders a class with the single method `run` and returns the statements of its body, each trimmed: the
     * parameters of the method are all named `value`.
     */
    fun writeBody(
        returns: TypeDef,
        vararg parameters: TypeDef,
        body: (ExpressionDef, List<ExpressionDef>) -> StatementDef
    ): String {
        val method = MethodDef.builder("run")
            .addModifiers(Modifier.PUBLIC)
            .returns(returns)
        parameters.forEach { method.addParameter("value", it) }
        val classDef = ClassDef.builder("test.MyClass")
            .addModifiers(Modifier.PUBLIC)
            .addMethod(method.build(body))
            .build()
        return render(classDef).lines()
            .dropWhile { !it.contains("fun run") }
            .drop(1)
            .takeWhile { !it.trim().startsWith("}") }
            .joinToString("\n") { it.trim() }
    }

    /**
     * Compiles the given sources together and fails the test if compilation reports any error.
     *
     * @param sources The Kotlin sources
     */
    fun assertCompiles(vararg sources: String) {
        compileAndLoad(*sources).use { }
    }

    /** Compiles sources and exposes the generated classes for behavioral assertions. */
    fun compileAndLoad(vararg sources: String): URLClassLoader {
        val sourceDir = Files.createTempDirectory("sourcegen-kotlin-sources")
        val outputDir = Files.createTempDirectory("sourcegen-kotlin-classes")
        sources.forEachIndexed { index, source ->
            Files.writeString(sourceDir.resolve("Source$index.kt"), source)
        }
        val errors = mutableListOf<String>()
        val collector = object : MessageCollector {
            override fun clear() = errors.clear()

            override fun hasErrors() = errors.isNotEmpty()

            override fun report(
                severity: CompilerMessageSeverity,
                message: String,
                location: CompilerMessageSourceLocation?
            ) {
                if (severity.isError) {
                    errors.add(if (location == null) message else "${location.path}:${location.line}: $message")
                }
            }
        }
        val arguments = K2JVMCompilerArguments().apply {
            freeArgs = listOf(sourceDir.toString())
            destination = outputDir.toString()
            classpath = System.getProperty("java.class.path")
            noStdlib = true
            noReflect = true
            jvmTarget = "17"
        }
        val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)
        if (exitCode != ExitCode.OK || errors.isNotEmpty()) {
            fail<Unit>(
                "Generated source does not compile:\n" + errors.joinToString("\n") +
                    "\n\n" + sources.joinToString("\n\n")
            )
        }
        return URLClassLoader(arrayOf(outputDir.toUri().toURL()), javaClass.classLoader)
    }
}
