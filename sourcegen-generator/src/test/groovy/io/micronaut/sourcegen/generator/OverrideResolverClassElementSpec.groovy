package io.micronaut.sourcegen.generator

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.ast.ClassElement
import io.micronaut.sourcegen.model.ClassDef
import io.micronaut.sourcegen.model.ClassTypeDef
import io.micronaut.sourcegen.model.ExpressionDef
import io.micronaut.sourcegen.model.MethodDef
import io.micronaut.sourcegen.model.TypeDef

import javax.lang.model.element.Modifier

/**
 * OverrideResolver over supertypes only the compiler knows: javac elements.
 */
class OverrideResolverClassElementSpec extends AbstractTypeElementSpec {

    private static MethodDef erasedGet() {
        MethodDef.builder("get").addModifiers(Modifier.PUBLIC).overrides().returns(TypeDef.OBJECT)
            .build((self, p) -> ExpressionDef.nullValue().returning())
    }

    void "an element supertype named as a class element passes its variable on"() {
        given:
        ClassElement element = buildClassElement('''
package test;
abstract class Relay<X> extends Holder<java.util.List<X>> {
}
abstract class Holder<T> {
    public abstract T get();
}
''')
        def get = erasedGet()
        def impl = ClassDef.builder("test.RelayImpl")
            .superclass(TypeDef.parameterized(new ClassTypeDef.ClassElementType(element, false), TypeDef.STRING))
            .addMethod(get)
            .build()

        when:
        def resolved = OverrideResolver.resolve(impl, get, GenerationScope.none())

        then:
        resolved != null
        resolved.returnType() == TypeDef.parameterized(ClassTypeDef.of(List), TypeDef.STRING)
    }

    void "a variable an intermediate element passes on is not taken for a variable of the declaring type of the same name"() {
        given:
        ClassElement element = buildClassElement('''
package test;
abstract class Relay<X> extends Holder<java.util.List<X>> {
}
abstract class Holder<T> {
    public abstract T get();
}
''')
        def get = erasedGet()
        def impl = ClassDef.builder("test.RelayImpl")
            .addTypeVariable(TypeDef.variable("X"))
            .superclass(TypeDef.parameterized(new ClassTypeDef.ClassElementType(element, false), TypeDef.STRING))
            .addMethod(get)
            .build()

        when:
        def resolved = OverrideResolver.resolve(impl, get, GenerationScope.none())
        def errors = javacErrors(impl, RELAY_SOURCES)

        then:
        errors == []
        resolved != null
        resolved.returnType() == TypeDef.parameterized(ClassTypeDef.of(List), TypeDef.STRING)
    }

    private static final Map<String, String> RELAY_SOURCES = [
        'test.Relay' : 'package test; public abstract class Relay<X> extends Holder<java.util.List<X>> { }',
        'test.Holder': 'package test; public abstract class Holder<T> { public abstract T get(); }'
    ]

    /** Renders the definition with the Java generator and compiles it with the given sources: the errors javac reports. */
    private static List<String> javacErrors(ClassDef definition, Map<String, String> sources) {
        def writer = new StringWriter()
        new io.micronaut.sourcegen.JavaPoetSourceGenerator().write(definition, writer)
        def compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        def diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>()
        def all = new LinkedHashMap<String, String>(sources)
        all.put(definition.getName(), writer.toString())
        def files = all.collect { name, text ->
            new javax.tools.SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), javax.tools.JavaFileObject.Kind.SOURCE) {
                @Override
                CharSequence getCharContent(boolean ignoreEncodingErrors) { text }
            }
        }
        def output = java.nio.file.Files.createTempDirectory("override-resolver")
        compiler.getTask(null, null, diagnostics, ['-d', output.toString()], null, files).call()
        diagnostics.diagnostics.findAll { it.kind == javax.tools.Diagnostic.Kind.ERROR }
            .collect { it.getMessage(Locale.ROOT) + "\n" + writer }
    }
}
