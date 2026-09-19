package io.micronaut.sourcegen

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.junit.jupiter.api.Assertions.fail
import java.nio.file.Files
import java.net.URLClassLoader

/**
 * Compiles generated sources so that a test can assert the output is not only well formed but also
 * valid Kotlin.
 */
object KotlinCompileAssertions {

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
