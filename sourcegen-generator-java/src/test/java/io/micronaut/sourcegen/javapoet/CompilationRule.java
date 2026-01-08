package io.micronaut.sourcegen.javapoet;

import static com.google.common.base.Preconditions.checkState;
import static com.google.testing.compile.Compilation.Status.SUCCESS;
import static com.google.testing.compile.Compiler.javac;

import com.google.common.collect.ImmutableSet;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * A JUnit 5 extension that executes tests such that instances of {@link Elements} and
 * {@link Types} are available during execution.
 *
 * <p>To use this extension in a test, add the following to your test class:</p>
 *
 * <pre>{@code
 * @ExtendWith(CompilationRule.class)
 * class MyTest {
 *
 *   @Test
 *   void usesElements(CompilationRule compilation) {
 *     Elements elements = compilation.getElements();
 *     Types types = compilation.getTypes();
 *     // ...
 *   }
 * }
 * }</pre>
 */
public final class CompilationRule implements BeforeEachCallback,
    AfterEachCallback, InvocationInterceptor, ParameterResolver {

  private static final JavaFileObject DUMMY =
      JavaFileObjects.forSourceLines("Dummy", "final class Dummy {}");

  private Elements elements;
  private Types types;

  @Override
  public void beforeEach(ExtensionContext context) {
    elements = null;
    types = null;
  }

  @Override
  public void afterEach(ExtensionContext context) {
    elements = null;
    types = null;
  }

  @Override
  public void interceptTestMethod(Invocation<Void> invocation,
      ReflectiveInvocationContext<java.lang.reflect.Method> reflectiveContext,
      ExtensionContext extensionContext) throws Throwable {

    EvaluatingProcessor evaluatingProcessor = new EvaluatingProcessor(invocation);
    Compilation compilation = javac().withProcessors(evaluatingProcessor).compile(DUMMY);
    checkState(compilation.status().equals(SUCCESS), compilation);
    evaluatingProcessor.throwIfStatementThrew();
  }

  /**
   * Returns the {@link Elements} instance associated with the current execution of the extension.
   *
   * @throws IllegalStateException if this method is invoked outside the execution of the
   * extension.
   */
  public Elements getElements() {
    checkState(elements != null, "Not running within the rule");
    return elements;
  }

  /**
   * Returns the {@link Types} instance associated with the current execution of the extension.
   *
   * @throws IllegalStateException if this method is invoked outside the execution of the
   * extension.
   */
  public Types getTypes() {
    checkState(types != null, "Not running within the rule");
    return types;
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {
    return parameterContext.getParameter().getType() == CompilationRule.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext,
                                 ExtensionContext extensionContext) {
    return this;
  }

  final class EvaluatingProcessor extends AbstractProcessor {

    private final Invocation<Void> invocation;
    private Throwable thrown;

    EvaluatingProcessor(Invocation<Void> invocation) {
      this.invocation = invocation;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
      return SourceVersion.latest();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
      return ImmutableSet.of("*");
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
      super.init(processingEnv);
      elements = processingEnv.getElementUtils();
      types = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
      // just run the test on the last round after compilation is over
      if (roundEnv.processingOver()) {
        try {
          invocation.proceed();
        } catch (Throwable e) {
          thrown = e;
        }
      }
      return false;
    }

    /** Throws what the test invocation threw, if anything. */
    void throwIfStatementThrew() throws Throwable {
      if (thrown != null) {
        throw thrown;
      }
    }
  }
}
